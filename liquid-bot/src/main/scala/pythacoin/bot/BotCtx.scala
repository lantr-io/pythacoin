package pythacoin.bot

import pythacoin.{AppCtx, given}
import scalus.cardano.address.Address
import scalus.cardano.ledger.ScriptHash
import scalus.cardano.node.stream.{BackupSource, ChainSyncSource}
import scalus.cardano.node.stream.ox.OxBlockchainStreamProvider

import scala.concurrent.ExecutionContext.Implicits.global

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
    val wallet: Wallet
) extends AutoCloseable {
    def scriptAddr: Address = appCtx.scriptAddr
    def policyId: ScriptHash = appCtx.policyId

    /** Multi-line operator-facing summary of every knob in effect. Use at
      * startup to make a misconfiguration obvious before the bot does any work.
      */
    def show: String = {
        def addr(a: Address): String = a.encode.getOrElse(a.toHex)
        s"""Pythacoin bot context:
           |  network            = ${cfg.network}
           |  relay              = ${cfg.relayHost}:${cfg.relayPort} (magic ${cfg.networkMagic})
           |  appId              = ${cfg.appId}
           |  cdp script address = ${addr(scriptAddr)}
           |  cdp policy id      = ${policyId.toHex}
           |  pyth policy id     = ${cfg.pythPolicyIdHex}
           |  wallet             = ${addr(wallet.address)}
           |  minLtvBps          = ${cfg.minLtvBps}
           |  minProfitLovelace  = ${cfg.minProfitLovelace}
           |  dryRun             = ${cfg.dryRun}""".stripMargin
    }

    override def close(): Unit = streamProvider.close()
}

object BotCtx {

    def apply(cfg: BotConfig): BotCtx = {
        val appCtx = AppCtx(cfg.network, cfg.blockfrostApiKey, cfg.pythPolicyIdHex, cfg.pythKey)
        val provider = OxBlockchainStreamProvider.create(
          appId = cfg.appId,
          cardanoInfo = appCtx.cardanoInfo,
          chainSync = ChainSyncSource.N2N(cfg.relayHost, cfg.relayPort, cfg.networkMagic),
          // Reuse the provider AppCtx already built (Blockfrost mainnet/preprod
          // or Yaci local), instead of opening a second Blockfrost connection
          // and duplicating the network-selection logic.
          backup = BackupSource.Custom(appCtx.provider)
        )
        val wallet = Wallet.fromConfig(cfg)
        new BotCtx(cfg, appCtx, provider, wallet)
    }
}
