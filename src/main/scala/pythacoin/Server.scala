package pythacoin

import scalus.cardano.address.{Address, Network as ScalusNetwork}
import scalus.cardano.ledger.{CardanoInfo, ScriptHash}
import scalus.cardano.node.{BlockchainProvider, BlockfrostProvider}
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.Data
import scalus.utils.await
import sttp.client4.DefaultFutureBackend
import sttp.tapir.*
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

given sttp.client4.Backend[scala.concurrent.Future] = DefaultFutureBackend()

case class AppCtx(
    cardanoInfo: CardanoInfo,
    provider: BlockchainProvider,
    pythPolicyId: ScriptHash,
    cdpScript: PlutusV3[Data => Unit]
) {
    lazy val policyId: ScriptHash = cdpScript.script.scriptHash
    lazy val scriptAddr: Address = cdpScript.address(cardanoInfo.network)
}

object AppCtx {

    def apply(
        network: ScalusNetwork,
        blockfrostApiKey: String,
        pythPolicyIdHex: String
    ): AppCtx = {
        val provider =
            if network == ScalusNetwork.Mainnet then
                BlockfrostProvider.mainnet(blockfrostApiKey).await(30.seconds)
            else if network == ScalusNetwork.Testnet then
                BlockfrostProvider.preprod(blockfrostApiKey).await(30.seconds)
            else sys.error(s"Unsupported network: $network")

        val pythPolicy = ScriptHash.fromHex(pythPolicyIdHex)
        val cdpScript = CdpContract(pythPolicy)

        new AppCtx(provider.cardanoInfo, provider, pythPolicy, cdpScript)
    }

    def yaciDevKit(pythPolicyIdHex: String): AppCtx = {
        val provider = BlockfrostProvider.localYaci().await(30.seconds)
        val pythPolicy = ScriptHash.fromHex(pythPolicyIdHex)
        val cdpScript = CdpContract(pythPolicy)

        new AppCtx(provider.cardanoInfo, provider, pythPolicy, cdpScript)
    }
}

class Server(ctx: AppCtx):
    private val getPrice = endpoint.get
        .in("price")
        .out(stringBody)
        .errorOut(stringBody)
        .handle(_ => Right("TODO: fetch price from Pyth"))

    private val listCdps = endpoint.get
        .in("cdps")
        .out(stringBody)
        .errorOut(stringBody)
        .handle(_ => Right("TODO: list CDPs"))

    private val apiEndpoints = List(getPrice, listCdps)

    private val swaggerEndpoints = SwaggerInterpreter()
        .fromEndpoints[[X] =>> X](apiEndpoints.map(_.endpoint), "Pythacoin", "0.1")

    def start(): Unit =
        NettySyncServer()
            .port(8088)
            .addEndpoints(apiEndpoints ++ swaggerEndpoints)
            .startAndWait()
