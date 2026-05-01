package pythacoin.bot

import org.slf4j.LoggerFactory
import scalus.cardano.ledger.{AssetName, TransactionInput, TransactionOutput, Utxo, Utxos}
import scalus.cardano.node.{UtxoQuery, UtxoSource}
import scalus.cardano.node.stream.*
import scalus.uplc.builtin.ByteString.utf8
import scalus.utils.Hex.toHex

import scala.collection.concurrent.TrieMap

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

    /** Live view of CDP UTxOs keyed by `TransactionInput`. `TrieMap.readOnlySnapshot`
      * gives the consumer a frozen O(1) view of the map at the moment of the
      * snapshot, so iteration in `onChange` can't race with concurrent
      * `Created`/`Spent`/`RolledBack` events.
      */
    private val cdpView = TrieMap.empty[TransactionInput, TransactionOutput]

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
            if cdpView.remove(utxo.input).isDefined then
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
      *
      * If the snapshot query fails, keep the previous view: a transient
      * connectivity blip shouldn't wipe state we'd then have to wait for the
      * next on-chain event to rediscover.
      */
    private def reseed(): Unit = {
        // OxBlockchainStreamProvider is direct-style (Id[A] = A), so no `.await`.
        ctx.streamProvider
            .findUtxos(UtxoQuery(UtxoSource.FromAddress(ctx.scriptAddr))) match
            case Right(fresh: Utxos) =>
                cdpView.clear()
                for (input, output) <- fresh if isCdp(output) do cdpView.put(input, output)
            case Left(err) =>
                log.error(s"Failed to re-seed CDP view (keeping previous view): $err")
    }

    /** A UTxO is a CDP iff it carries an inline datum and exactly one non-PUSD
      * token under our policy (the CDP NFT). Mirrors `CdpQueries.parseCdpInfo`
      * and `CdpValidator.scala` which both rely on the one-NFT invariant.
      */
    private def isCdp(output: TransactionOutput): Boolean = {
        if output.inlineDatum.isEmpty then false
        else
            val assets = output.value.assets.assets.getOrElse(ctx.policyId, Map.empty)
            assets.count { case (name, _) => name != pusdAsset } == 1
    }

    /** Point-in-time snapshot of the CDP view. `readOnlySnapshot` is O(1) on
      * `TrieMap` and immune to concurrent mutation during the consumer's
      * iteration.
      */
    private def snapshot: Iterable[Utxo] =
        cdpView.readOnlySnapshot().iterator.map(Utxo.apply.tupled).toVector
}
