package pythacoin.bot

import pythacoin.CardanoNet
import scalus.cardano.node.stream.engine.{ChainStore, ChainStoreUtxoSet}
import scalus.cardano.node.stream.engine.snapshot.ChainStoreRestorer
import scalus.cardano.node.stream.{ChainTip, SnapshotSource}

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

/** Wraps the scalus `ChainStoreRestorer` with the per-network Mithril
  * aggregator endpoint, so the bot's bootstrap path needs to know only
  * `BotConfig.cardanoNet`, the genesis verification key, and an on-disk
  * work dir.
  *
  * The genesis verification key is intentionally not baked in — it's a
  * per-network constant published at
  * https://mithril.network/doc/manual/getting-started/network-configurations
  * and shipping it inline would turn an upstream key rotation into a
  * silent-update security bug. The caller (the future BotApp wiring)
  * reads it from env (`PYTHACOIN_MITHRIL_GENESIS_VK`) and hands it in.
  *
  * The aggregator URL has a sensible per-network default and an env
  * override (`PYTHACOIN_MITHRIL_AGGREGATOR_URL`) for staging endpoints
  * or for the day Mithril relocates a host.
  */
object MithrilBootstrap {

    final case class Endpoints(aggregatorUrl: String, genesisVerificationKey: String)

    /** Default Mithril aggregator URL for the given network. Sourced from
      * Mithril's published network configurations; verify against that
      * page when bumping `scalusNodeVersion`.
      */
    def defaultAggregatorUrl(net: CardanoNet): String = net match
        case CardanoNet.Mainnet =>
            "https://aggregator.release-mainnet.api.mithril.network/aggregator"
        case CardanoNet.Preprod =>
            "https://aggregator.release-preprod.api.mithril.network/aggregator"
        case CardanoNet.Preview =>
            "https://aggregator.pre-release-preview.api.mithril.network/aggregator"

    /** Run a Mithril V2 snapshot restore into `store`. The restore is
      * resumable across runs as long as `workDir` is preserved, so the
      * caller should point this at a stable directory (for example,
      * `${PYTHACOIN_CHAIN_STORE_DIR}/../mithril-cache-${network}`).
      *
      * The caller decides whether to bootstrap at all (typically: only
      * when the chain store is empty); this helper just runs the restore
      * unconditionally and returns the resulting tip.
      */
    def restore(
        net: CardanoNet,
        genesisVerificationKey: String,
        store: ChainStore & ChainStoreUtxoSet,
        workDir: Path,
        aggregatorUrlOverride: Option[String] = None
    )(using ExecutionContext): Future[ChainTip] = {
        val source = SnapshotSource.Mithril(
          aggregatorUrl = aggregatorUrlOverride.getOrElse(defaultAggregatorUrl(net)),
          genesisVerificationKey = genesisVerificationKey,
          workDir = workDir
        )
        ChainStoreRestorer(store).restore(source)
    }

    /** Restore from an already-extracted cardano-database directory
      * (`<dir>/immutable/`, `<dir>/ledger/<slot>/`). Skips download +
      * cryptographic verification — useful for CI fixtures and tests
      * that pre-stage a snapshot via the `mithril-client` CLI.
      */
    def restoreDir(
        store: ChainStore & ChainStoreUtxoSet,
        snapshotDir: Path
    )(using ExecutionContext): Future[ChainTip] =
        ChainStoreRestorer(store).restore(SnapshotSource.MithrilDir(snapshotDir))
}
