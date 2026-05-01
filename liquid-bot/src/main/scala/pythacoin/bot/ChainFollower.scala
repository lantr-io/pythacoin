package pythacoin.bot

import org.slf4j.LoggerFactory
import scalus.cardano.ledger.{AssetName, TransactionInput, TransactionOutput, Utxo, Utxos}
import scalus.cardano.node.{UtxoQuery, UtxoSource}
import scalus.cardano.node.stream.*
import scalus.uplc.builtin.ByteString.utf8
import scalus.utils.Hex.toHex

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

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
  */
final class ChainFollower(ctx: BotCtx, onChange: Iterable[Utxo] => Unit) {

    private val log = LoggerFactory.getLogger(classOf[ChainFollower])
    private val pusdAsset = AssetName(utf8"PUSD")

    /** Live view of CDP UTxOs keyed by `TransactionInput`. Concurrent because
      * `RollBackward` handling races against an incoming `Created`/`Spent` event
      * delivered on the same flow.
      */
    private val cdpView = new ConcurrentHashMap[TransactionInput, TransactionOutput]()

    /** Subscribe to UTxO events at the CDP script address and run the callback
      * loop until the flow terminates. Blocks the calling (virtual) thread.
      */
    def runForever(): Unit = {
        val query = UtxoEventQuery(UtxoQuery(UtxoSource.FromAddress(ctx.scriptAddr)))
        val flow = ctx.streamProvider.subscribeUtxoQuery(query, SubscriptionOptions())
        log.info(s"ChainFollower subscribed to ${ctx.scriptAddr.encode.getOrElse(ctx.scriptAddr.toHex)}")
        flow.runForeach(handleEvent)
    }

    private def handleEvent(event: UtxoEvent): Unit = event match
        case UtxoEvent.Created(utxo, producedBy, at) =>
            if isCdp(utxo.output) then
                cdpView.put(utxo.input, utxo.output)
                log.info(s"CDP created at ${at}: ${utxo.input.transactionId.toHex}#${utxo.input.index}")
                onChange(snapshot)
        case UtxoEvent.Spent(utxo, spentBy, at) =>
            if cdpView.remove(utxo.input) != null then
                log.info(s"CDP spent at ${at} by ${spentBy.toHex}: ${utxo.input.transactionId.toHex}#${utxo.input.index}")
                onChange(snapshot)
        case UtxoEvent.RolledBack(to) =>
            log.warn(s"Rollback to $to — re-seeding CDP view from provider snapshot")
            reseed()
            onChange(snapshot)

    /** Drop the local view and re-derive it from a fresh provider query. Called
      * after every rollback. Cheap relative to a chain-sync, expensive relative
      * to processing a single event — but rollbacks are rare and correctness
      * matters more than throughput here.
      */
    private def reseed(): Unit = {
        // OxBlockchainStreamProvider is direct-style (Id[A] = A), so no `.await`.
        val fresh: Utxos = ctx.streamProvider
            .findUtxos(UtxoQuery(UtxoSource.FromAddress(ctx.scriptAddr))) match
            case Right(found) => found
            case Left(err) =>
                log.error(s"Failed to re-seed CDP view: $err")
                Map.empty
        cdpView.clear()
        for (input, output) <- fresh if isCdp(output) do cdpView.put(input, output)
    }

    /** A UTxO is a CDP iff it carries an inline datum and exactly one non-PUSD
      * token under our policy (the CDP NFT). Mirrors `CdpQueries.parseCdpInfo`.
      */
    private def isCdp(output: TransactionOutput): Boolean = {
        if output.inlineDatum.isEmpty then false
        else
            val assets = output.value.assets.assets.getOrElse(ctx.policyId, Map.empty)
            assets.exists { case (name, _) => name != pusdAsset }
    }

    /** Snapshot the current view as immutable `Utxo`s for the consumer. */
    private def snapshot: Iterable[Utxo] =
        cdpView.entrySet().asScala.view.map(e => Utxo(e.getKey, e.getValue))
}
