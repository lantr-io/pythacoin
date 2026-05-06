package pythacoin.bot

import org.slf4j.LoggerFactory
import pythacoin.{Assets, CdpInfo}
import scalus.cardano.ledger.{Utxo, Utxos}
import scalus.cardano.node.NodeSubmitError
import scalus.uplc.builtin.ByteString
import scalus.utils.Hex.toHex
import scalus.utils.await

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Owns the per-bot mutable state needed to coordinate the chain-event and price-push triggers: a
  * busy gate and a "view changed during in-flight pass" retry flag. Exposes `tryEvaluate` for both
  * triggers and `markViewDirty` for the rollback path.
  *
  * Splitting this off `BotApp` removes the `(evalBusy, viewDirty, ctx, ...)` parameter sprawl that
  * crept into the chain-event handler, the price loop, and the evaluator itself.
  */
final class Evaluator(ctx: BotCtx) {

    private val log = LoggerFactory.getLogger(classOf[Evaluator])

    private val evalBusy = new AtomicBoolean(false)
    // Set true on Reseeded; cleared on a successful tryEvaluate. priceLoop
    // re-fires while this is true even without a fresh WS push, so a
    // Reseeded that arrives during an in-flight pass is retried within
    // ~50 ms (one priceLoop tick) instead of waiting for the next push.
    private val viewDirty = new AtomicBoolean(false)

    // Telemetry counters surfaced via BotHandle.
    private val evaluationsCounter = new AtomicLong(0L)
    private val candidatesCounter = new AtomicLong(0L)
    private val attemptsCounter = new AtomicLong(0L)
    private val submissionsCounter = new AtomicLong(0L)

    def evaluationsRun: Long = evaluationsCounter.get()
    def liquidationCandidates: Long = candidatesCounter.get()
    def liquidationsAttempted: Long = attemptsCounter.get()
    def liquidationsSubmitted: Long = submissionsCounter.get()

    /** Mark the view dirty so the next priceLoop tick retries. */
    def markViewDirty(): Unit = viewDirty.set(true)

    /** Read (and don't clear) the dirty flag. */
    def isViewDirty: Boolean = viewDirty.get()

    /** Clear the dirty flag — call only after a successful re-evaluation. */
    def clearViewDirty(): Unit = viewDirty.set(false)

    /** Skip-if-busy wrapper. Returns `true` if `evaluate` ran, `false` if the trigger was dropped.
      * Callers can use the return value to keep a "retry next tick" flag set on a dropped pass.
      */
    def tryEvaluate(cdps: Iterable[(Utxo, CdpInfo)]): Boolean = {
        if cdps.isEmpty then return false
        if !evalBusy.compareAndSet(false, true) then
            log.debug("tryEvaluate: previous pass still running, skipping")
            return false
        try {
            // The Boolean tells the caller "I took the slot" so they don't
            // re-mark dirty for retry. The counter, by contrast, must only
            // tick when the decider actually ran — a stale-cache short-circuit
            // is not forward progress.
            if evaluate(cdps) then evaluationsCounter.incrementAndGet()
            true
        } finally evalBusy.set(false)
    }

    /** Run the decider over the given CDPs against the cached Pyth price and the bot's current PUSD
      * balance. One cache read + one wallet UTxO query per call, regardless of how many CDPs are
      * evaluated.
      *
      * Returns `true` if the decider was actually invoked (i.e. a fresh cached price was
      * available); `false` if we short-circuited because the cache was empty or stale. No REST
      * fallback — a stale cache means we should not act.
      */
    private def evaluate(cdps: Iterable[(Utxo, CdpInfo)]): Boolean = {
        val now = Instant.now()
        val cached = ctx.priceCache.current(now, ctx.cfg.priceMaxAgeSeconds) match
            case Some(c) => c
            case None =>
                log.warn("Cached price unavailable or stale; skipping decision pass")
                return false

        val pusdUtxos: Utxos = walletPusdUtxos()
        val availablePusd: Long = pusdUtxos.values.map(_.value.asset(ctx.policyId, Assets.Pusd)).sum

        cdps.foreach { case (cdpUtxo, info) =>
            LiquidationDecider.decide(
              info,
              cached.priceRaw,
              ctx.cfg.minLtvBps,
              availablePusd,
              ctx.cfg.minProfitLovelace
            ) match
                case LiquidationDecider.Decision.Skip(reason) =>
                    log.debug(s"Skip ${info.nftName}: $reason")
                case LiquidationDecider.Decision.Liquidate(ltvBps) =>
                    log.info(s"Liquidate candidate ${info.nftName} at LTV $ltvBps bps")
                    candidatesCounter.incrementAndGet()
                    if !ctx.cfg.dryRun then
                        submitLiquidation(cdpUtxo, info, pusdUtxos, now, cached.updateBytes)
        }
        true
    }

    private def submitLiquidation(
        cdpUtxo: Utxo,
        info: CdpInfo,
        pusdUtxos: Utxos,
        now: Instant,
        cachedPriceBytes: ByteString
    ): Unit = {
        attemptsCounter.incrementAndGet()
        try
            // Greedy-pick only the PUSD UTxOs we actually need to cover this
            // CDP's debt — passing the whole wallet bloats the tx on a
            // fragmented wallet.
            val selectedPusd = PusdSelection.greedy(pusdUtxos, ctx.policyId, info.debtPusd)
            val builder = ctx.appCtx.cdpTransactions.liquidateCdp(
              cdpUtxo,
              ctx.wallet.address,
              selectedPusd,
              now,
              Some(cachedPriceBytes)
            )
            // `complete` needs `BlockchainReader[Future]`; the Ox stream provider
            // is direct-style so we route the read path through `appCtx.provider`
            // (Blockfrost). Submission still goes through `streamProvider.submit`.
            val completed =
                builder.complete(ctx.appCtx.provider, ctx.wallet.address).await(30.seconds)
            val signed = ctx.wallet.sign(completed.transaction)
            ctx.streamProvider.submit(signed) match
                case Right(hash) =>
                    submissionsCounter.incrementAndGet()
                    log.info(s"Liquidate submitted: txid=${hash.toHex} nft=${info.nftName}")
                case Left(_: NodeSubmitError.UtxoNotAvailable) =>
                    // Could be the CDP itself (a competing liquidator won the race)
                    // or one of our own PUSD UTxOs (spent by a prior liquidation
                    // already in flight). Both are benign retry-on-next-event cases.
                    log.info(
                      s"Liquidate ${info.nftName}: a required input UTxO is no longer available"
                    )
                case Left(other) =>
                    log.warn(s"Liquidate submit rejected for ${info.nftName}: ${other.message}")
        catch
            case e: Exception =>
                log.error(s"Liquidate failed for ${info.nftName}: ${e.getMessage}", e)
    }

    /** UTxOs at the bot wallet that hold PUSD. Used as the spend set for `liquidateCdp` (PUSD lives
      * at the wallet, not the script address, so the tx-builder can't auto-select).
      */
    private def walletPusdUtxos(): Utxos = {
        val result = ctx.streamProvider
            .queryUtxos(_.output.address == ctx.wallet.address)
            .execute()
        result match
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
