package pythacoin.bot

import com.monovore.decline.{Command, Opts}
import org.slf4j.LoggerFactory
import ox.{ExitCode, Ox, OxApp, useCloseableInScope}
import pythacoin.{CdpInfo, given}
import pythacoin.onchain.CdpDatum
import scalus.cardano.ledger.{AssetName, Utxo}
import scalus.cardano.node.{NodeSubmitError, UtxoQuery, UtxoSource}
import scalus.uplc.builtin.ByteString.utf8
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
  * Extending `OxApp` gives us:
  *   - a `supervised { … }` scope around `run` (so `useCloseableInScope` works),
  *   - a JVM shutdown hook that cancels the main fork on SIGINT/SIGTERM, which
  *     interrupts `runForever`'s blocking `Await.result` and lets the scope
  *     unwind cleanly,
  *   - configurable `handleException` / `handleInterruptedException` callbacks.
  */
object BotApp extends OxApp {

    private val log = LoggerFactory.getLogger(BotApp.getClass)
    private val pusdAsset = AssetName(utf8"PUSD")

    /** `Cmd` carries the parsed subcommand intent. Single field today (whether
      * `dry-run` was specified on the CLI), kept as an enum so adding a future
      * subcommand is a one-case extension rather than a flag-soup refactor.
      */
    private enum Cmd { case DryRun, Start }

    private val command: Command[Cmd] = {
        val dryRun = Opts.subcommand("dry-run", "Follow the chain and log candidate liquidations") {
            Opts(Cmd.DryRun)
        }
        val start = Opts.subcommand("start", "Run the liquidation bot") {
            Opts(Cmd.Start)
        }
        Command(name = "pythacoin-bot", header = "Pythacoin liquidation keeper")(dryRun orElse start)
    }

    override def run(args: Vector[String])(using Ox): ExitCode =
        command.parse(args) match
            case Left(help) =>
                println(help); ExitCode.Failure(2)
            case Right(cmd) =>
                val envCfg = BotConfig.fromEnv()
                val cfg = envCfg.copy(dryRun = (cmd == Cmd.DryRun) || envCfg.dryRun)
                runWithConfig(cfg); ExitCode.Success

    /** Bot lifecycle given an already-built config. Public so tests can drive
      * it with an in-code `BotConfig` instead of going through env vars.
      */
    def runWithConfig(cfg: BotConfig)(using Ox): Unit = {
        val ctx = useCloseableInScope(BotCtx(cfg))
        log.info(s"Starting\n${ctx.show}")
        new ChainFollower(ctx, onCdpChange(ctx)).runForever()
    }

    /** Re-evaluate every CDP each time the view changes. v1 is intentionally
      * un-debounced: the CDP set churns on the order of seconds, and re-pricing
      * is cheap (one Pyth fetch + one balance lookup per evaluation pass).
      */
    private def onCdpChange(ctx: BotCtx)(cdps: Iterable[Utxo]): Unit = {
        val now = Instant.now()
        val updateBytes =
            try ctx.appCtx.pythClient.fetchPriceUpdate()
            catch case e: Exception =>
                log.warn(s"Pyth fetch failed; skipping decision pass: ${e.getMessage}")
                return
        val priceRaw = parsePriceRaw(updateBytes.bytes)
        val availablePusd = currentPusdBalance(ctx)

        cdps.foreach { cdpUtxo =>
            parseCdp(ctx, cdpUtxo) match
                case None => ()
                case Some(info) =>
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
                            if !ctx.cfg.dryRun then submitLiquidation(ctx, cdpUtxo, info, now)
        }
    }

    private def submitLiquidation(
        ctx: BotCtx,
        cdpUtxo: Utxo,
        info: CdpInfo,
        now: Instant
    ): Unit = {
        try
            // Stream provider is direct-style; no Future to await.
            val pusdUtxos = ctx.streamProvider
                .findUtxos(UtxoQuery(UtxoSource.FromAddress(ctx.wallet.address))) match
                case Right(found) =>
                    found.filter { case (_, output) =>
                        output.value.asset(ctx.policyId, pusdAsset) > 0
                    }
                case Left(err) =>
                    throw RuntimeException(s"liquidator UTxO query failed: $err")

            val builder =
                ctx.appCtx.cdpTransactions.liquidateCdp(cdpUtxo, ctx.wallet.address, pusdUtxos, now)
            // `complete` needs a `BlockchainReader` (Future-specialised). The
            // BlockfrostProvider in appCtx satisfies that; the stream provider
            // does not. Submission still goes through streamProvider so we hit
            // the engine's local mempool path before the Blockfrost backup.
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

    private def parseCdp(ctx: BotCtx, utxo: Utxo): Option[CdpInfo] = {
        val output = utxo.output
        val assets = output.value.assets.assets.getOrElse(ctx.policyId, Map.empty)
        val nftEntries = assets.filter { case (name, _) => name != pusdAsset }
        for {
            (nftName, _) <- nftEntries.headOption
            datumData <- output.inlineDatum
        } yield {
            val datum = datumData.to[CdpDatum]
            CdpInfo(
              nftName = nftName.bytes.toHex,
              owner = datum.owner.hash.toHex,
              collateralLovelace = output.value.coin.value,
              debtPusd = datum.debt.toLong,
              ltv = 0.0
            )
        }
    }

    private def currentPusdBalance(ctx: BotCtx): Long = {
        ctx.streamProvider
            .findUtxos(UtxoQuery(UtxoSource.FromAddress(ctx.wallet.address))) match
            case Right(found) =>
                found.values.map(_.value.asset(ctx.policyId, pusdAsset)).sum
            case Left(err) =>
                // Don't conflate "we couldn't ask" with "balance is zero" —
                // operators reading the log would otherwise top up PUSD for no
                // reason. Returning 0 still skips submission this pass.
                log.warn(s"PUSD balance query failed (treating as 0 for this pass): $err")
                0L
    }

    /** Parse the Pyth ADA/USD integer price from the update bytes. Mirrors
      * `PythClient.parsePrice` but returns the raw integer (BigInt) so we can
      * feed `LiquidationDecider` without the BigDecimal round-trip used for
      * display purposes.
      */
    private def parsePriceRaw(bytes: Array[Byte]): BigInt = {
        import java.nio.{ByteBuffer, ByteOrder}
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val priceOffset = 116 + 6
        buf.position(priceOffset)
        BigInt(buf.getLong())
    }
}
