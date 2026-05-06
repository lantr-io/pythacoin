package pythacoin.bot

import org.slf4j.LoggerFactory
import pythacoin.{AppCtx, given}
import scalus.cardano.address.Address
import scalus.cardano.ledger.ScriptHash
import scalus.cardano.node.stream.{BackupSource, ChainSyncSource, StreamProviderConfig}
import scalus.cardano.node.stream.engine.{ChainStore, ChainStoreUtxoSet, KvChainStore}
import scalus.cardano.node.stream.engine.kvstore.rocksdb.RocksDbKvStore
import scalus.cardano.node.stream.ox.OxBlockchainStreamProvider

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

/** Application context for the bot — analogue of `pythacoin.AppCtx`, but the
  * provider is a `BlockchainStreamProvider` instead of a plain `BlockchainProvider`,
  * and we carry a `Wallet` for autonomous signing.
  *
  * Construction wires:
  *   - the embedded scalus-node engine via N2N to the configured relay,
  *   - `appCtx.provider` as the snapshot/submit fallback (Blockfrost mainnet/preprod
  *     or Yaci local — the choice lives in `AppCtx`, not here),
  *   - the existing pythacoin `AppCtx` for tx-builders and Pyth client reuse.
  *
  * The bot does not need its own copy of any pythacoin core logic — all
  * tx-building goes through `appCtx.cdpTransactions`, all Pyth I/O through
  * `appCtx.pythClient`, all CDP parsing through `appCtx.cdpQueries`.
  */
final class BotCtx(
    val cfg: BotConfig,
    val appCtx: AppCtx,
    val streamProvider: OxBlockchainStreamProvider,
    val wallet: Wallet,
    val priceCache: PriceCache
) extends AutoCloseable {
    def scriptAddr: Address = appCtx.scriptAddr
    def policyId: ScriptHash = appCtx.policyId

    /** Multi-line operator-facing summary of every knob in effect. Use at
      * startup to make a misconfiguration obvious before the bot does any work.
      */
    def show: String =
        s"""Pythacoin bot context:
           |  network            = ${cfg.cardanoNet}
           |  relay              = ${cfg.relayHost}:${cfg.relayPort} (magic ${cfg.cardanoNet.magic})
           |  appId              = ${cfg.appId}
           |  cdp script address = ${BotCtx.renderAddress(scriptAddr)}
           |  cdp policy id      = ${policyId.toHex}
           |  pyth policy id     = ${appCtx.pythPolicyId.toHex}
           |  wallet             = ${BotCtx.renderAddress(wallet.address)}
           |  minLtvBps          = ${cfg.minLtvBps}
           |  minProfitLovelace  = ${cfg.minProfitLovelace}
           |  pyth ws url        = ${cfg.pythWsUrl}
           |  pyth channel       = ${cfg.pythChannel.wireName}
           |  price max age      = ${cfg.priceMaxAgeSeconds} s
           |  chain store dir    = ${cfg.chainStoreDir.getOrElse("(none — ephemeral)")}
           |  bootstrap          = ${BotCtx.renderBootstrap(cfg)}
           |  dryRun             = ${cfg.dryRun}""".stripMargin

    override def close(): Unit = streamProvider.close()
}

object BotCtx {

    private val log = LoggerFactory.getLogger(BotCtx.getClass)

    /** Bech32 if the address has a hrp, otherwise hex. Shared by the startup
      * banner and the chain-follower subscription log.
      */
    def renderAddress(a: Address): String = a.encode.getOrElse(a.toHex)

    private def renderBootstrap(cfg: BotConfig): String = cfg.bootstrap match
        case BootstrapMode.None    => "none"
        case BootstrapMode.Mithril =>
            val agg = cfg.mithrilAggregatorUrl.getOrElse(
              MithrilBootstrap.defaultAggregatorUrl(cfg.cardanoNet)
            )
            s"mithril (aggregator=$agg, workDir=${cfg.mithrilWorkDir.getOrElse("?")})"

    /** Run the configured bootstrap exactly once, when the chain store is
      * empty. A warm restart (tip already persisted) is a no-op regardless
      * of mode, so leaving `PYTHACOIN_BOOTSTRAP=mithril` on across runs is
      * safe — only the first run pays the download cost.
      *
      * Failures are surfaced rather than swallowed: if the operator asked
      * for a Mithril snapshot and we can't deliver one, syncing from
      * genesis silently is the wrong fallback (it could waste hours).
      */
    private def maybeBootstrap(
        cfg: BotConfig,
        store: Option[ChainStore & ChainStoreUtxoSet]
    ): Unit = cfg.bootstrap match
        case BootstrapMode.None => ()
        case BootstrapMode.Mithril =>
            val s = store.getOrElse(sys.error(
              "PYTHACOIN_BOOTSTRAP=mithril requires PYTHACOIN_CHAIN_STORE_DIR — there is " +
                  "nowhere to restore into without a persistent store"
            ))
            if s.tip.isDefined then
                log.info("Mithril bootstrap: chain store already has a tip; skipping restore")
            else
                val workDir = Paths.get(cfg.mithrilWorkDir.getOrElse(sys.error(
                  "PYTHACOIN_BOOTSTRAP=mithril requires PYTHACOIN_MITHRIL_WORKDIR — the " +
                      "Mithril artefact is multi-GB and the work dir must persist for resumability"
                )))
                Files.createDirectories(workDir)
                val genesisVk = cfg.mithrilGenesisVk.getOrElse(sys.error(
                  "PYTHACOIN_BOOTSTRAP=mithril requires PYTHACOIN_MITHRIL_GENESIS_VK — the " +
                      "per-network genesis verification key is published at " +
                      "https://mithril.network/doc/manual/getting-started/network-configurations"
                ))
                runMithrilBootstrap(cfg, s, workDir, genesisVk)

    private def runMithrilBootstrap(
        cfg: BotConfig,
        store: ChainStore & ChainStoreUtxoSet,
        workDir: Path,
        genesisVk: String
    ): Unit = {
        val aggregator = cfg.mithrilAggregatorUrl.getOrElse(
          MithrilBootstrap.defaultAggregatorUrl(cfg.cardanoNet)
        )
        log.info(
          s"Mithril bootstrap: restoring ${cfg.cardanoNet} snapshot from $aggregator " +
              s"(workDir=$workDir)"
        )
        val started = System.nanoTime()
        val tip = Await.result(
          MithrilBootstrap.restore(
            net = cfg.cardanoNet,
            genesisVerificationKey = genesisVk,
            store = store,
            workDir = workDir,
            aggregatorUrlOverride = cfg.mithrilAggregatorUrl
          ),
          Duration.Inf
        )
        val elapsedSec = (System.nanoTime() - started) / 1_000_000_000L
        log.info(
          s"Mithril bootstrap: restored to slot ${tip.point.slot} (block ${tip.blockNo}) " +
              s"in ${elapsedSec}s"
        )
    }

    def apply(cfg: BotConfig): BotCtx = {
        val appCtx = AppCtx(cfg.cardanoNet, cfg.blockfrostApiKey, cfg.pythPolicyIdHex, cfg.pythKey)

        val maybeChainStore: Option[ChainStore & ChainStoreUtxoSet] = cfg.chainStoreDir.map { dir =>
            val path = Paths.get(dir)
            // createDirectories is a no-op if the dir already exists; RocksDB
            // will tell us whether it created fresh or recovered from the LOG.
            Files.createDirectories(path)
            log.info(s"ChainStore: opening RocksDB at $path")
            new KvChainStore(RocksDbKvStore.open(path))
        }

        maybeBootstrap(cfg, maybeChainStore)

        val streamCfg = StreamProviderConfig(
          appId = cfg.appId,
          cardanoInfo = appCtx.cardanoInfo,
          chainSync = ChainSyncSource.N2N(cfg.relayHost, cfg.relayPort, cfg.cardanoNet.magic),
          // Reuse the provider AppCtx already built (Blockfrost mainnet/preprod/preview)
          // instead of opening a second Blockfrost connection.
          backup = BackupSource.Custom(appCtx.provider),
          chainStore = maybeChainStore
        )
        val provider = OxBlockchainStreamProvider.create(streamCfg)
        val wallet = Wallet.fromConfig(cfg)
        new BotCtx(cfg, appCtx, provider, wallet, new PriceCache)
    }
}
