package pythacoin.bot

import org.slf4j.LoggerFactory
import pythacoin.CdpInfo
import scalus.cardano.ledger.{TransactionInput, Utxo, Utxos}
import scalus.cardano.node.stream.*
import scalus.cardano.node.stream.UtxoEventQueryMacros.buildEventQuery
import scalus.utils.Hex.toHex

import scala.collection.concurrent.TrieMap
import java.util.concurrent.atomic.AtomicBoolean

/** Delta delivered to the consumer when the CDP view changes. The bot uses these to scope per-event
  * work to what actually changed:
  *   - `Added` ⇒ re-price just that one CDP,
  *   - `Removed` ⇒ no work (the CDP is gone),
  *   - `Reseeded` ⇒ rollback path, re-price the whole view.
  *
  * Price-driven re-evaluation (a sliding price flipping previously-healthy CDPs into liquidatable
  * ones with no on-chain event) is a separate concern not driven by this stream — operators can run
  * a periodic ticker if needed.
  */
sealed trait CdpEvent
object CdpEvent {
    case class Added(utxo: Utxo, info: CdpInfo) extends CdpEvent
    case class Removed(input: TransactionInput) extends CdpEvent
    case class Reseeded(all: collection.Map[TransactionInput, (Utxo, CdpInfo)]) extends CdpEvent
}

/** Maintains a live view of every CDP UTxO at the script address, driven by the embedded
  * scalus-node's `BlockchainStreamProvider` callback API.
  *
  * The view is correct because:
  *   - the stream is resumed from a persisted `ChainPoint` on each restart so events are never
  *     silently dropped,
  *   - `UtxoEvent.RolledBack` is handled explicitly by re-seeding from the provider snapshot at the
  *     rollback target,
  *   - `includeExistingUtxos = true` (default) seeds the view with whatever the backup provider
  *     knows at subscription time, so we don't have to wait for a `Created` event to learn about
  *     existing CDPs.
  *
  * Each entry stores the parsed `CdpInfo` alongside the `Utxo` so the consumer never has to
  * re-decode the inline datum on every event.
  */
final class ChainFollower(ctx: BotCtx, onChange: CdpEvent => Unit) {

    private val log = LoggerFactory.getLogger(classOf[ChainFollower])

    private val cdpView = TrieMap.empty[TransactionInput, (Utxo, CdpInfo)]

    // True iff `cdpView` is consistent with the latest applied chain point.
    // Flipped to false on a failed rollback reseed; restored to true once a
    // subsequent reseed succeeds. While false, `snapshot()` returns None so
    // the WS-driven price loop can't drive evaluations against an
    // inconsistent view.
    private val viewValid = new AtomicBoolean(true)

    /** Read-only snapshot of the live CDP view, keyed by the CDP UTxO's `TransactionInput`. Returns
      * `None` when the view is known to be inconsistent (a rollback whose reseed query failed).
      *
      * The WS-driven price-trigger pass uses this so a stale view from an abandoned fork can't
      * drive a fee-burning submission. A successful reseed on a later event restores `Some`.
      *
      * `TrieMap.readOnlySnapshot()` is O(1) and safe to call concurrently with `handleEvent`. `get`
      * / `contains` on the returned snapshot are O(log n); `size`, however, is O(n) — prefer
      * iterating `values` once if you need a count.
      */
    def snapshot(): Option[collection.Map[TransactionInput, (Utxo, CdpInfo)]] =
        if viewValid.get() then Some(cdpView.readOnlySnapshot()) else None

    /** Subscribe to UTxO events at the CDP script address and run the callback loop until the flow
      * terminates. Blocks the calling (virtual) thread.
      */
    def runForever(): Unit = {
        val query = buildEventQuery((u, _) => u.output.address == ctx.scriptAddr)
        val flow = ctx.streamProvider.subscribeUtxoQuery(query, SubscriptionOptions())
        log.info(s"ChainFollower subscribed to ${BotCtx.renderAddress(ctx.scriptAddr)}")
        flow.runForeach(handleEvent)
    }

    private def handleEvent(event: UtxoEvent): Unit = {
        // Opportunistically retry a previously-failed reseed on every
        // incoming event so a transient `findUtxos` blip self-recovers
        // without waiting for the next on-chain rollback.
        if !viewValid.get() then attemptReseed("retry after prior failed reseed")

        event match
            case UtxoEvent.Created(utxo, _, at) =>
                ctx.appCtx.cdpQueries.parseCdpInfo(utxo.output) match
                    case Some(info) =>
                        cdpView.put(utxo.input, (utxo, info))
                        log.info(
                          s"CDP created at $at: ${utxo.input.transactionId.toHex}#${utxo.input.index}"
                        )
                        if viewValid.get() then onChange(CdpEvent.Added(utxo, info))
                    case None => ()
            case UtxoEvent.Spent(utxo, spentBy, at) =>
                if cdpView.remove(utxo.input).isDefined then
                    log.info(
                      s"CDP spent at $at by ${spentBy.toHex}: ${utxo.input.transactionId.toHex}#${utxo.input.index}"
                    )
                    if viewValid.get() then onChange(CdpEvent.Removed(utxo.input))
            case UtxoEvent.RolledBack(to) =>
                attemptReseed(s"rollback to $to")
    }

    /** Attempt to re-seed the view. On success, mark valid and emit Reseeded. On failure, mark
      * invalid (snapshot will return None until a later event triggers another retry that
      * succeeds).
      */
    private def attemptReseed(reason: String): Unit = {
        log.warn(s"Re-seeding CDP view: $reason")
        if reseed() then
            viewValid.set(true)
            onChange(CdpEvent.Reseeded(cdpView.readOnlySnapshot()))
        else viewValid.set(false)
    }

    /** Drop the local view and re-derive it from a fresh provider query. Returns `true` on success,
      * `false` if the snapshot query failed (in which case the previous view is preserved — a
      * transient connectivity blip shouldn't wipe state we'd then have to wait for the next
      * on-chain event to rediscover).
      */
    private def reseed(): Boolean = {
        val result = ctx.streamProvider
            .queryUtxos(_.output.address == ctx.scriptAddr)
            .execute()
        result match
            case Right(fresh: Utxos) =>
                cdpView.clear()
                for
                    (input, output) <- fresh
                    info <- ctx.appCtx.cdpQueries.parseCdpInfo(output)
                do cdpView.put(input, (Utxo(input, output), info))
                true
            case Left(err) =>
                log.error(
                  s"Failed to re-seed CDP view (keeping previous view, snapshot will report invalid): $err"
                )
                false
    }
}
