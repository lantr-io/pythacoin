package pythacoin

import scalus.cardano.address.Address
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

    /** Create AppCtx for mainnet, preprod, or preview using Blockfrost. */
    def apply(
        net: CardanoNet,
        blockfrostApiKey: String,
        pythPolicyIdHex: String,
        pythKey: String
    ): AppCtx = {
        val (provider, baseUrl) = net match
            case CardanoNet.Mainnet =>
                (BlockfrostProvider.mainnet(blockfrostApiKey).await(30.seconds),
                  BlockfrostProvider.mainnetUrl)
            case CardanoNet.Preprod =>
                (BlockfrostProvider.preprod(blockfrostApiKey).await(30.seconds),
                  BlockfrostProvider.preprodUrl)
            case CardanoNet.Preview =>
                (BlockfrostProvider.preview(blockfrostApiKey).await(30.seconds),
                  BlockfrostProvider.previewUrl)

        val pythPolicy = ScriptHash.fromHex(pythPolicyIdHex)
        val cdpScript = CdpContract(pythPolicy)

        new AppCtx(provider.cardanoInfo, provider, blockfrostApiKey, baseUrl, pythPolicy, pythKey, cdpScript)
    }

    /** Create AppCtx for local development using Yaci DevKit (no real Pyth oracle). */
    def yaciDevKit(pythPolicyIdHex: String): AppCtx = {
        val provider = BlockfrostProvider.localYaci().await(30.seconds)
        val pythPolicy = ScriptHash.fromHex(pythPolicyIdHex)
        val cdpScript = CdpContract(pythPolicy)

        new AppCtx(provider.cardanoInfo, provider, "", "http://localhost:8080/api/v1", pythPolicy, "", cdpScript)
    }
}
