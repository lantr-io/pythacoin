package pythacoin.bot

import com.monovore.decline.{Command, Opts}
import org.slf4j.LoggerFactory
import ox.{ExitCode, Ox, OxApp, useCloseableInScope}
import pythacoin.{Assets, CdpInfo}
import scalus.cardano.ledger.{Utxo, Utxos}
import scalus.cardano.node.{NodeSubmitError, UtxoQuery, UtxoSource}
import scalus.utils.Hex.toHex
import scalus.utils.await

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Entry point for the liquidation bot. Two subcommands:
  *   - `dry-run`  — connect, follow the chain, log candidate liquidations, never submit.
  *   - `start`    — same, but submit liquidation transactions for under-collateralised CDPs.
  *
  * `start` honours `PYTHACOIN_DRY_RUN=true` as an extra safety net.
  *
  * Extending `OxApp` gives us a `supervised` scope around `run` (so
  * `useCloseableInScope` works), a JVM shutdown hook that cancels the main fork
  * on SIGINT/SIGTERM, and configurable exception callbacks via `OxApp.Settings`.
  */
object BotApp extends OxApp {

    private val log = LoggerFactory.getLogger(BotApp.getClass)

    private val command: Command[Boolean] = {
        val dryRun = Opts.subcommand("dry-run", "Follow the chain and log candidate liquidations") {
            Opts(true)
        }
        val start = Opts.subcommand("start", "Run the liquidation bot") {
            Opts(false)
        }
        Command(name = "pythacoin-bot", header = "Pythacoin liquidation keeper")(dryRun orElse start)
    }

    override def run(args: Vector[String])(using Ox): ExitCode =
        command.parse(args) match
            case Left(help) =>
                println(help); ExitCode.Failure(2)
            case Right(forceDryRun) =>
                val envCfg = BotConfig.fromEnv()
                val cfg = envCfg.copy(dryRun = forceDryRun || envCfg.dryRun)
                runWithConfig(cfg); ExitCode.Success

    /** Bot lifecycle given an already-built config. Public so tests can drive
      * it with an in-code `BotConfig` instead of going through env vars.
      */
    def runWithConfig(cfg: BotConfig)(using Ox): Unit = {
        val ctx = useCloseableInScope(BotCtx(cfg))
        log.info(s"Starting\n${ctx.show}")
        new ChainFollower(ctx, onCdpChange(ctx)).runForever()
    }

    /** Per-event dispatch: scope the work to what actually changed.
      *   - `Added`    → re-price just that one CDP,
      *   - `Removed`  → no work (the CDP is gone),
      *   - `Reseeded` → rollback path, re-price the whole view.
      */
    private def onCdpChange(ctx: BotCtx)(event: CdpEvent): Unit = event match
        case CdpEvent.Added(utxo, info) => evaluate(ctx, Iterable((utxo, info)))
        case CdpEvent.Removed(_)        => ()
        case CdpEvent.Reseeded(all)     => evaluate(ctx, all)

    /** Run the decider over the given CDPs against a fresh Pyth price and the
      * bot's current PUSD balance. One Pyth fetch + one wallet UTxO query per
      * call, regardless of how many CDPs are evaluated.
      */
    private def evaluate(ctx: BotCtx, cdps: Iterable[(Utxo, CdpInfo)]): Unit = {
        if cdps.isEmpty then return
        val now = Instant.now()
        val priceRaw =
            try BigInt(ctx.appCtx.pythClient.parsePriceRaw(ctx.appCtx.pythClient.fetchPriceUpdate()))
            catch case e: Exception =>
                log.warn(s"Pyth fetch failed; skipping decision pass: ${e.getMessage}")
                return

        val pusdUtxos: Utxos = walletPusdUtxos(ctx)
        val availablePusd: Long = pusdUtxos.values.map(_.value.asset(ctx.policyId, Assets.Pusd)).sum

        cdps.foreach { case (cdpUtxo, info) =>
            LiquidationDecider.decide(
              info,
              priceRaw,
              ctx.cfg.minLtvBps,
              availablePusd,
              ctx.cfg.minProfitLovelace
            ) match
                case LiquidationDecider.Decision.Skip(reason) =>
                    log.debug(s"Skip ${info.nftName}: $reason")
                case LiquidationDecider.Decision.Liquidate(ltvBps) =>
                    log.info(s"Liquidate candidate ${info.nftName} at LTV $ltvBps bps")
                    if !ctx.cfg.dryRun then submitLiquidation(ctx, cdpUtxo, info, pusdUtxos, now)
        }
    }

    private def submitLiquidation(
        ctx: BotCtx,
        cdpUtxo: Utxo,
        info: CdpInfo,
        pusdUtxos: Utxos,
        now: Instant
    ): Unit = {
        try
            // Greedy-pick only the PUSD UTxOs we actually need to cover this
            // CDP's debt — passing the whole wallet bloats the tx on a
            // fragmented wallet.
            val selectedPusd =
                PusdSelection.greedy(pusdUtxos, ctx.policyId, info.debtPusd)
            val builder =
                ctx.appCtx.cdpTransactions.liquidateCdp(cdpUtxo, ctx.wallet.address, selectedPusd, now)
            // `complete` needs `BlockchainReader[Future]`; the Ox stream provider
            // is direct-style so we route the read path through `appCtx.provider`
            // (Blockfrost). Submission still goes through `streamProvider.submit`.
            val completed = builder.complete(ctx.appCtx.provider, ctx.wallet.address).await(30.seconds)
            val signed = ctx.wallet.sign(completed.transaction)
            ctx.streamProvider.submit(signed) match
                case Right(hash) =>
                    log.info(s"Liquidate submitted: txid=${hash.toHex} nft=${info.nftName}")
                case Left(_: NodeSubmitError.UtxoNotAvailable) =>
                    // Could be the CDP itself (a competing liquidator won the race)
                    // or one of our own PUSD UTxOs (spent by a prior liquidation
                    // already in flight). Both are benign retry-on-next-event cases.
                    log.info(s"Liquidate ${info.nftName}: a required input UTxO is no longer available")
                case Left(other) =>
                    log.warn(s"Liquidate submit rejected for ${info.nftName}: ${other.message}")
        catch case e: Exception =>
            log.error(s"Liquidate failed for ${info.nftName}: ${e.getMessage}", e)
    }

    /** UTxOs at the bot wallet that hold PUSD. Used as the spend set for
      * `liquidateCdp` (PUSD lives at the wallet, not the script address, so the
      * tx-builder can't auto-select).
      */
    private def walletPusdUtxos(ctx: BotCtx): Utxos = {
        ctx.streamProvider
            .findUtxos(UtxoQuery(UtxoSource.FromAddress(ctx.wallet.address))) match
            case Right(found) =>
                found.filter { case (_, output) =>
                    output.value.asset(ctx.policyId, Assets.Pusd) > 0
                }
            case Left(err) =>
                // Don't conflate "we couldn't ask" with "no PUSD" — operators
                // reading the log would otherwise top up for no reason. Empty
                // map skips submission this pass, and the next event retries.
                log.warn(s"Wallet UTxO query failed (treating as 0 PUSD for this pass): $err")
                Map.empty
    }
}
