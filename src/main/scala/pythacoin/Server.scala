package pythacoin

import scalus.cardano.address.{Address, Network as ScalusNetwork}
import scalus.cardano.ledger.*
import scalus.cardano.node.{BlockchainProvider, BlockfrostProvider}
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.{ByteString, Data}
import scalus.utils.Hex.{hexToBytes, toHex}
import scalus.utils.{await, showDetailedHighlighted}
import sttp.client4.DefaultFutureBackend
import scalus.cardano.address.{ShelleyAddress, ShelleyPaymentPart}
import scalus.cardano.onchain.plutus.v1.PubKeyHash
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.upickle.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

given sttp.client4.Backend[Future] = DefaultFutureBackend()

case class AppCtx(
    cardanoInfo: CardanoInfo,
    provider: BlockchainProvider,
    blockfrostApiKey: String,
    blockfrostBaseUrl: String,
    pythPolicyId: ScriptHash,
    pythKey: String,
    cdpScript: PlutusV3[Data => Unit]
) {
    lazy val policyId: ScriptHash = cdpScript.script.scriptHash
    lazy val scriptAddr: Address = cdpScript.address(cardanoInfo.network)
    lazy val pythClient: PythClient = PythClient(pythPolicyId, pythKey, blockfrostApiKey, blockfrostBaseUrl, provider)
    lazy val cdpQueries: CdpQueries = CdpQueries(this)
    lazy val cdpTransactions: CdpTransactions = {
        given CardanoInfo = cardanoInfo
        CdpTransactions(this, pythClient)
    }
}

object AppCtx {

    def apply(
        network: ScalusNetwork,
        blockfrostApiKey: String,
        pythPolicyIdHex: String,
        pythKey: String
    ): AppCtx = {
        val (provider, baseUrl) =
            if network == ScalusNetwork.Mainnet then
                (BlockfrostProvider.mainnet(blockfrostApiKey).await(30.seconds), "https://cardano-mainnet.blockfrost.io/api/v0")
            else if network == ScalusNetwork.Testnet then
                (BlockfrostProvider.preprod(blockfrostApiKey).await(30.seconds), "https://cardano-preprod.blockfrost.io/api/v0")
            else sys.error(s"Unsupported network: $network")

        val pythPolicy = ScriptHash.fromHex(pythPolicyIdHex)
        val cdpScript = CdpContract(pythPolicy)

        new AppCtx(provider.cardanoInfo, provider, blockfrostApiKey, baseUrl, pythPolicy, pythKey, cdpScript)
    }

    def yaciDevKit(pythPolicyIdHex: String): AppCtx = {
        val provider = BlockfrostProvider.localYaci().await(30.seconds)
        val pythPolicy = ScriptHash.fromHex(pythPolicyIdHex)
        val cdpScript = CdpContract(pythPolicy)

        new AppCtx(provider.cardanoInfo, provider, "", "http://localhost:8080/api/v1", pythPolicy, "", cdpScript)
    }
}

// --- Request/Response types ---

case class OpenCdpRequest(
    collateralAda: Double,
    borrowPusd: Double,
    ownerAddress: String
) derives upickle.default.ReadWriter

case class BorrowRequest(
    nftName: String,
    amount: Double,
    ownerAddress: String
) derives upickle.default.ReadWriter

case class RepayRequest(
    nftName: String,
    amount: Double,
    ownerAddress: String
) derives upickle.default.ReadWriter

case class CloseRequest(
    nftName: String,
    ownerAddress: String
) derives upickle.default.ReadWriter

case class LiquidateRequest(
    nftName: String,
    liquidatorAddress: String
) derives upickle.default.ReadWriter

case class TxResponse(
    txCborHex: String
) derives upickle.default.ReadWriter

class Server(ctx: AppCtx):
    private given CardanoInfo = ctx.cardanoInfo

    /** Parse address from hex (CIP-30) or bech32 string. */
    private def parseAddress(addr: String): Address =
        val parsed = if addr.startsWith("addr") then Address.fromBech32(addr)
        else Address.fromBytes(addr.hexToBytes)
        Log.info(s"Parsed address: ${addr.take(20)}... -> $parsed")
        parsed

    // --- GET /price ---
    private val getPrice = endpoint.get
        .in("price")
        .out(jsonBody[PriceInfo])
        .errorOut(stringBody)
        .handle { _ =>
            try
                val updateBytes = ctx.pythClient.fetchPriceUpdate()
                val price = ctx.pythClient.parsePrice(updateBytes)
                Right(PriceInfo(
                  adaUsd = price.toDouble,
                  timestamp = Instant.now().toString
                ))
            catch case e: Exception => Left(e.getMessage)
        }

    // --- GET /cdps ---
    private val listCdpsEndpoint = endpoint.get
        .in("cdps")
        .out(jsonBody[Seq[CdpInfo]])
        .errorOut(stringBody)
        .handle { _ =>
            try Right(ctx.cdpQueries.listCdps())
            catch case e: Exception => Left(e.getMessage)
        }

    // --- POST /cdp/open ---
    private val openCdpEndpoint = endpoint.post
        .in("cdp" / "open")
        .in(jsonBody[OpenCdpRequest])
        .out(jsonBody[TxResponse])
        .errorOut(stringBody)
        .handle { req =>
            try
                Log.info(s"POST /cdp/open: collateral=${req.collateralAda} ADA, borrow=${req.borrowPusd} PUSD")
                val ownerAddr = parseAddress(req.ownerAddress)
                val collateralLovelace = (req.collateralAda * 1_000_000).toLong
                val debtPusd = (req.borrowPusd * 1_000_000).toLong
                val ownerPkh = ownerAddr match
                    case s: ShelleyAddress => s.payment match
                        case ShelleyPaymentPart.Key(hash) => PubKeyHash(hash: ByteString)
                        case _ => throw RuntimeException("Owner address must have a key credential")
                    case _ => throw RuntimeException("Owner address must be a Shelley address")
                Log.info(s"Owner PKH: ${ownerPkh.hash.toHex}")
                val nftName = AssetName(ByteString.fromString("CDP-" + System.currentTimeMillis()))
                Log.info(s"NFT name: ${nftName.bytes.toHex}")
                val now = Instant.now()
                Log.info("Building openCdp transaction...")
                val builder = ctx.cdpTransactions.openCdp(
                  collateralLovelace, debtPusd, nftName, ownerPkh, ownerAddr, now
                )
                Log.info("Completing transaction...")
                val completed = builder.complete(ctx.provider, ownerAddr).await(30.seconds)
                val tx = completed.transaction
                Log.info(s"Transaction built successfully:\n${tx.showDetailedHighlighted}")
                Right(TxResponse(tx.toCbor.toHex))
            catch case e: Exception =>
                Log.error(s"POST /cdp/open failed: ${e.getMessage}", e)
                Left(e.getMessage)
        }

    // --- POST /cdp/borrow ---
    private val borrowEndpoint = endpoint.post
        .in("cdp" / "borrow")
        .in(jsonBody[BorrowRequest])
        .out(jsonBody[TxResponse])
        .errorOut(stringBody)
        .handle { req =>
            try
                Log.info(s"POST /cdp/borrow: nft=${req.nftName}, amount=${req.amount}")
                val ownerAddr = parseAddress(req.ownerAddress)
                val cdpUtxo = ctx.cdpQueries.findCdpUtxo(req.nftName).getOrElse(
                  throw RuntimeException(s"CDP not found: ${req.nftName}")
                )
                val amount = (req.amount * 1_000_000).toLong
                val now = Instant.now()
                val builder = ctx.cdpTransactions.borrowPusd(cdpUtxo, amount, ownerAddr, now)
                val completed = builder.complete(ctx.provider, ownerAddr).await(30.seconds)
                val tx = completed.transaction
                Log.info(s"Borrow tx built:\n${tx.showDetailedHighlighted}")
                Right(TxResponse(tx.toCbor.toHex))
            catch case e: Exception =>
                Log.error(s"POST /cdp/borrow failed: ${e.getMessage}", e)
                Left(e.getMessage)
        }

    // --- POST /cdp/repay ---
    private val repayEndpoint = endpoint.post
        .in("cdp" / "repay")
        .in(jsonBody[RepayRequest])
        .out(jsonBody[TxResponse])
        .errorOut(stringBody)
        .handle { req =>
            try
                Log.info(s"POST /cdp/repay: nft=${req.nftName}, amount=${req.amount}")
                val ownerAddr = parseAddress(req.ownerAddress)
                val cdpUtxo = ctx.cdpQueries.findCdpUtxo(req.nftName).getOrElse(
                  throw RuntimeException(s"CDP not found: ${req.nftName}")
                )
                val amount = (req.amount * 1_000_000).toLong
                val now = Instant.now()
                val builder = ctx.cdpTransactions.repayPusd(cdpUtxo, amount, ownerAddr, now)
                val completed = builder.complete(ctx.provider, ownerAddr).await(30.seconds)
                val tx = completed.transaction
                Log.info(s"Repay tx built:\n${tx.showDetailedHighlighted}")
                Right(TxResponse(tx.toCbor.toHex))
            catch case e: Exception =>
                Log.error(s"POST /cdp/repay failed: ${e.getMessage}", e)
                Left(e.getMessage)
        }

    // --- POST /cdp/close ---
    private val closeEndpoint = endpoint.post
        .in("cdp" / "close")
        .in(jsonBody[CloseRequest])
        .out(jsonBody[TxResponse])
        .errorOut(stringBody)
        .handle { req =>
            try
                Log.info(s"POST /cdp/close: nft=${req.nftName}")
                val ownerAddr = parseAddress(req.ownerAddress)
                val cdpUtxo = ctx.cdpQueries.findCdpUtxo(req.nftName).getOrElse(
                  throw RuntimeException(s"CDP not found: ${req.nftName}")
                )
                val now = Instant.now()
                val builder = ctx.cdpTransactions.closeCdp(cdpUtxo, ownerAddr, now)
                val completed = builder.complete(ctx.provider, ownerAddr).await(30.seconds)
                val tx = completed.transaction
                Log.info(s"Close tx built:\n${tx.showDetailedHighlighted}")
                Right(TxResponse(tx.toCbor.toHex))
            catch case e: Exception =>
                Log.error(s"POST /cdp/close failed: ${e.getMessage}", e)
                Left(e.getMessage)
        }

    // --- POST /cdp/liquidate ---
    private val liquidateEndpoint = endpoint.post
        .in("cdp" / "liquidate")
        .in(jsonBody[LiquidateRequest])
        .out(jsonBody[TxResponse])
        .errorOut(stringBody)
        .handle { req =>
            try
                Log.info(s"POST /cdp/liquidate: nft=${req.nftName}")
                val liquidatorAddr = parseAddress(req.liquidatorAddress)
                val cdpUtxo = ctx.cdpQueries.findCdpUtxo(req.nftName).getOrElse(
                  throw RuntimeException(s"CDP not found: ${req.nftName}")
                )
                val now = Instant.now()
                val builder = ctx.cdpTransactions.liquidateCdp(cdpUtxo, liquidatorAddr, now)
                val completed = builder.complete(ctx.provider, liquidatorAddr).await(30.seconds)
                val tx = completed.transaction
                Log.info(s"Liquidate tx built:\n${tx.showDetailedHighlighted}")
                Right(TxResponse(tx.toCbor.toHex))
            catch case e: Exception =>
                Log.error(s"POST /cdp/liquidate failed: ${e.getMessage}", e)
                Left(e.getMessage)
        }

    private val apiEndpoints: List[ServerEndpoint[Any, Identity]] = List(
      getPrice,
      listCdpsEndpoint,
      openCdpEndpoint,
      borrowEndpoint,
      repayEndpoint,
      closeEndpoint,
      liquidateEndpoint
    )

    private val swaggerEndpoints = SwaggerInterpreter()
        .fromServerEndpoints[Identity](apiEndpoints, "Pythacoin", "0.1")

    def start(): Unit =
        NettySyncServer()
            .port(8088)
            .addEndpoints(apiEndpoints ++ swaggerEndpoints)
            .startAndWait()
