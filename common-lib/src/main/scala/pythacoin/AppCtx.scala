package pythacoin

import scalus.cardano.address.{Address, Network as ScalusNetwork}
import scalus.cardano.ledger.*
import scalus.cardano.node.{BlockchainProvider, BlockfrostProvider}
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.Data
import scalus.utils.await
import sttp.client4.DefaultFutureBackend

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

/** Global sttp HTTP backend used by `PythClient` and `BlockfrostProvider`. */
given sttp.client4.Backend[Future] = DefaultFutureBackend()

/** Application context holding all shared state and services.
  * Lazily initializes derived objects (policy ID, script address, clients)
  * so that construction is fast and initialization errors surface at first use.
  */
case class AppCtx(
    cardanoInfo: CardanoInfo,
    provider: BlockchainProvider,
    blockfrostApiKey: String,
    blockfrostBaseUrl: String,
    pythPolicyId: ScriptHash,
    pythKey: String,
    cdpScript: PlutusV3[Data => Unit]
) {
    /** The script hash doubles as the minting policy ID for PUSD and CDP NFTs. */
    lazy val policyId: ScriptHash = cdpScript.script.scriptHash
    /** The script address where all CDP UTxOs live. */
    lazy val scriptAddr: Address = cdpScript.address(cardanoInfo.network)
    lazy val pythClient: PythClient = PythClient(pythPolicyId, pythKey, blockfrostApiKey, blockfrostBaseUrl, provider)
    lazy val cdpQueries: CdpQueries = CdpQueries(this)
    lazy val cdpTransactions: CdpTransactions = {
        given CardanoInfo = cardanoInfo
        CdpTransactions(this, pythClient)
    }
}

object AppCtx {

    /** Network identifier for AppCtx construction. Scalus's `Network` enum
      * collapses preview + preprod into `Testnet`, but we must distinguish
      * them here to pick the right Blockfrost endpoint.
      */
    enum BlockfrostNet { case Mainnet, Preprod, Preview }

    /** Create AppCtx for mainnet, preprod, or preview using Blockfrost. */
    def apply(
        net: BlockfrostNet,
        blockfrostApiKey: String,
        pythPolicyIdHex: String,
        pythKey: String
    ): AppCtx = {
        val (provider, baseUrl) = net match
            case BlockfrostNet.Mainnet =>
                (BlockfrostProvider.mainnet(blockfrostApiKey).await(30.seconds),
                  "https://cardano-mainnet.blockfrost.io/api/v0")
            case BlockfrostNet.Preprod =>
                (BlockfrostProvider.preprod(blockfrostApiKey).await(30.seconds),
                  "https://cardano-preprod.blockfrost.io/api/v0")
            case BlockfrostNet.Preview =>
                (BlockfrostProvider.preview(blockfrostApiKey).await(30.seconds),
                  "https://cardano-preview.blockfrost.io/api/v0")

        val pythPolicy = ScriptHash.fromHex(pythPolicyIdHex)
        val cdpScript = CdpContract(pythPolicy)

        new AppCtx(provider.cardanoInfo, provider, blockfrostApiKey, baseUrl, pythPolicy, pythKey, cdpScript)
    }

    /** Back-compat shim for the old (Network, ...) signature. Uses preprod for
      * `Testnet` since preview was added later — call the `(BlockfrostNet, ...)`
      * overload directly to opt into preview.
      */
    def apply(
        network: ScalusNetwork,
        blockfrostApiKey: String,
        pythPolicyIdHex: String,
        pythKey: String
    ): AppCtx = {
        val net = network match
            case ScalusNetwork.Mainnet => BlockfrostNet.Mainnet
            case ScalusNetwork.Testnet => BlockfrostNet.Preprod
            case other                 => sys.error(s"Unsupported network: $other")
        apply(net, blockfrostApiKey, pythPolicyIdHex, pythKey)
    }

    /** Create AppCtx for local development using Yaci DevKit (no real Pyth oracle). */
    def yaciDevKit(pythPolicyIdHex: String): AppCtx = {
        val provider = BlockfrostProvider.localYaci().await(30.seconds)
        val pythPolicy = ScriptHash.fromHex(pythPolicyIdHex)
        val cdpScript = CdpContract(pythPolicy)

        new AppCtx(provider.cardanoInfo, provider, "", "http://localhost:8080/api/v1", pythPolicy, "", cdpScript)
    }
}
