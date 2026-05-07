package pythacoin

import pythacoin.onchain.CdpDatum
import scalus.cardano.ledger.*
import scalus.uplc.builtin.ByteString
import scalus.utils.await

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Read-only queries against the blockchain to discover and inspect CDPs. All CDPs live at the
  * script address and are identified by a unique NFT under the script's policy ID.
  */
class CdpQueries(ctx: AppCtx) {

    private val policyId = ctx.policyId

    /** List all CDPs at the script address. */
    def listCdps(): Seq[CdpInfo] = {
        val utxos = ctx.provider.findUtxos(ctx.scriptAddr).await(30.seconds) match
            case Right(found) => found
            case Left(error)  => throw RuntimeException(s"Failed to query CDPs: $error")

        utxos.toSeq.flatMap { case (input, output) =>
            parseCdpInfo(output)
        }
    }

    /** Get a specific CDP by NFT name. */
    def getCdp(nftName: String): Option[CdpInfo] =
        listCdps().find(_.nftName == nftName)

    /** Find the UTxO for a specific CDP by NFT name. */
    def findCdpUtxo(nftName: String): Option[Utxo] = {
        val nftAsset = AssetName(ByteString.fromHex(nftName))
        val utxos = ctx.provider.findUtxos(ctx.scriptAddr).await(30.seconds) match
            case Right(found) => found
            case Left(error)  => throw RuntimeException(s"Failed to query CDPs: $error")

        utxos.collectFirst {
            case (input, output) if output.value.hasAsset(policyId, nftAsset) =>
                Utxo(input, output)
        }
    }

    /** Parse `CdpInfo` from a transaction output, if it's a valid CDP — i.e. it carries an inline
      * datum and exactly one non-PUSD token of quantity 1 under our policy (the CDP NFT). Public so
      * the bot can reuse the same predicate without duplicating it.
      */
    def parseCdpInfo(output: TransactionOutput): Option[CdpInfo] = {
        val assets = output.value.assets.assets.getOrElse(policyId, Map.empty)
        val nftEntries = assets.filter { case (name, _) => name != Assets.Pusd }

        nftEntries.toList match
            case (nftName, qty) :: Nil if qty == 1L =>
                output.inlineDatum.map { data =>
                    val datum = data.to[CdpDatum]
                    CdpInfo(
                      nftName = nftName.bytes.toHex,
                      owner = datum.owner.hash.toHex,
                      collateralLovelace = output.value.coin.value,
                      debtPusd = datum.debt.toLong,
                      ltv = 0.0 // computed by frontend with current price
                    )
                }
            case _ => None
    }
}

/** CDP state returned by the /cdps endpoint. LTV is set to 0 here — the frontend computes it using
  * the current price.
  */
case class CdpInfo(
    nftName: String,
    owner: String,
    collateralLovelace: Long,
    debtPusd: Long,
    ltv: Double
) derives upickle.default.ReadWriter

/** Current ADA/USD price returned by the /price endpoint. Includes the policy ID so the frontend
  * can identify PUSD tokens in the wallet.
  */
case class PriceInfo(
    adaUsd: Double,
    timestamp: String,
    policyId: String
) derives upickle.default.ReadWriter
