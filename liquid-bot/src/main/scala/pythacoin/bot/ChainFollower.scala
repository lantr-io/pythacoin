package pythacoin.bot

import org.slf4j.LoggerFactory
import pythacoin.CdpInfo
import scalus.cardano.ledger.{TransactionInput, Utxo, Utxos}
import scalus.cardano.node.{UtxoQuery, UtxoSource}
import scalus.cardano.node.stream.*
import scalus.utils.Hex.toHex

import scala.collection.concurrent.TrieMap

/** Delta delivered to the consumer when the CDP view changes. The bot uses
  * these to scope per-event work to what actually changed:
  *   - `Added`     ⇒ re-price just that one CDP,
  *   - `Removed`   ⇒ no work (the CDP is gone),
  *   - `Reseeded`  ⇒ rollback path, re-price the whole view.
  *
  * Price-driven re-evaluation (a sliding price flipping previously-healthy
  * CDPs into liquidatable ones with no on-chain event) is a separate concern
  * not driven by this stream — operators can run a periodic ticker if needed.
  */
sealed trait CdpEvent
object CdpEvent {
    case class Added(utxo: Utxo, info: CdpInfo)            extends CdpEvent
    case class Removed(input: TransactionInput)            extends CdpEvent
    case class Reseeded(all: Iterable[(Utxo, CdpInfo)])    extends CdpEvent
}

/** Maintains a live view of every CDP UTxO at the script address, driven by the
  * embedded scalus-node's `BlockchainStreamProvider` callback API.
  *
  * The view is correct because:
  *   - the stream is resumed from a persisted `ChainPoint` on each restart so
  *     events are never silently dropped,
  *   - `UtxoEvent.RolledBack` is handled explicitly by re-seeding from the
  *     provider snapshot at the rollback target,
  *   - `includeExistingUtxos = true` (default) seeds the view with whatever the
  *     backup provider knows at subscription time, so we don't have to wait for
  *     a `Created` event to learn about existing CDPs.
  *
  * Each entry stores the parsed `CdpInfo` alongside the `Utxo` so the consumer
  * never has to re-decode the inline datum on every event.
  */
final class ChainFollower(ctx: BotCtx, onChange: CdpEvent => Unit) {

    private val log = LoggerFactory.getLogger(classOf[ChainFollower])

    private val cdpView = TrieMap.empty[TransactionInput, (Utxo, CdpInfo)]

    /** Subscribe to UTxO events at the CDP script address and run the callback
      * loop until the flow terminates. Blocks the calling (virtual) thread.
      */
    def runForever(): Unit = {
        val query = UtxoEventQuery(UtxoQuery(UtxoSource.FromAddress(ctx.scriptAddr)))
        val flow = ctx.streamProvider.subscribeUtxoQuery(query, SubscriptionOptions())
        log.info(s"ChainFollower subscribed to ${BotCtx.renderAddress(ctx.scriptAddr)}")
        flow.runForeach(handleEvent)
    }

    private def handleEvent(event: UtxoEvent): Unit = event match
        case UtxoEvent.Created(utxo, _, at) =>
            ctx.appCtx.cdpQueries.parseCdpInfo(utxo.output) match
                case Some(info) =>
                    cdpView.put(utxo.input, (utxo, info))
                    log.info(s"CDP created at $at: ${utxo.input.transactionId.toHex}#${utxo.input.index}")
                    onChange(CdpEvent.Added(utxo, info))
                case None => ()
        case UtxoEvent.Spent(utxo, spentBy, at) =>
            if cdpView.remove(utxo.input).isDefined then
                log.info(s"CDP spent at $at by ${spentBy.toHex}: ${utxo.input.transactionId.toHex}#${utxo.input.index}")
                onChange(CdpEvent.Removed(utxo.input))
        case UtxoEvent.RolledBack(to) =>
            log.warn(s"Rollback to $to — re-seeding CDP view from provider snapshot")
            reseed()
            onChange(CdpEvent.Reseeded(cdpView.readOnlySnapshot().values))

    /** Drop the local view and re-derive it from a fresh provider query. Called
      * after every rollback.
      *
      * If the snapshot query fails, keep the previous view: a transient
      * connectivity blip shouldn't wipe state we'd then have to wait for the
      * next on-chain event to rediscover.
      */
    private def reseed(): Unit = {
        ctx.streamProvider
            .findUtxos(UtxoQuery(UtxoSource.FromAddress(ctx.scriptAddr))) match
            case Right(fresh: Utxos) =>
                cdpView.clear()
                for (input, output) <- fresh
                    info <- ctx.appCtx.cdpQueries.parseCdpInfo(output)
                do cdpView.put(input, (Utxo(input, output), info))
            case Left(err) =>
                log.error(s"Failed to re-seed CDP view (keeping previous view): $err")
    }
}
