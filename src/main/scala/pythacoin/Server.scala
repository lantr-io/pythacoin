package pythacoin

import scalus.cardano.address.{Address, Network as ScalusNetwork}
import scalus.cardano.ledger.{AddrKeyHash, CardanoInfo, ScriptHash}
import scalus.cardano.node.{BlockchainProvider, BlockfrostProvider}
import scalus.cardano.txbuilder.TransactionSigner
import scalus.cardano.wallet.hd.HdAccount
import scalus.cardano.onchain.plutus.v1.PubKeyHash
import scalus.crypto.ed25519.Ed25519Signer
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.{ByteString, Data}
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
    account: HdAccount,
    signer: TransactionSigner,
    pythPolicyId: ScriptHash,
    cdpScript: PlutusV3[Data => Unit]
) {
    lazy val pubKeyHash: PubKeyHash = PubKeyHash(account.paymentKeyHash)
    lazy val addrKeyHash: AddrKeyHash = account.paymentKeyHash
    lazy val address: Address = account.baseAddress(cardanoInfo.network)
    lazy val policyId: ScriptHash = cdpScript.script.scriptHash
    lazy val scriptAddr: Address = cdpScript.address(cardanoInfo.network)
}

object AppCtx {

    def apply(
        network: ScalusNetwork,
        mnemonic: String,
        blockfrostApiKey: String,
        pythPolicyIdHex: String
    )(using Ed25519Signer): AppCtx = {
        val provider =
            if network == ScalusNetwork.Mainnet then
                BlockfrostProvider.mainnet(blockfrostApiKey).await(30.seconds)
            else if network == ScalusNetwork.Testnet then
                BlockfrostProvider.preview(blockfrostApiKey).await(30.seconds)
            else sys.error(s"Unsupported network: $network")

        val account = HdAccount.fromMnemonic(mnemonic)
        val pythPolicy = ScriptHash.fromHex(pythPolicyIdHex)
        val cdpScript = CdpContract(pythPolicy)

        new AppCtx(
          provider.cardanoInfo,
          provider,
          account,
          account.signerForUtxos,
          pythPolicy,
          cdpScript
        )
    }

    def yaciDevKit(pythPolicyIdHex: String)(using Ed25519Signer): AppCtx = {
        val mnemonic =
            "test test test test test test test test test test test test test test test test test test test test test test test sauce"
        val provider = BlockfrostProvider.localYaci().await(30.seconds)
        val account = HdAccount.fromMnemonic(mnemonic)
        val pythPolicy = ScriptHash.fromHex(pythPolicyIdHex)
        val cdpScript = CdpContract(pythPolicy)

        new AppCtx(
          provider.cardanoInfo,
          provider,
          account,
          account.signerForUtxos,
          pythPolicy,
          cdpScript
        )
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
