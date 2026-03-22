package pythacoin

import scalus.cardano.address.{Network, StakeAddress, StakePayload}
import scalus.cardano.ledger.*
import scalus.cardano.node.BlockchainProvider
import scalus.uplc.builtin.ByteString
import scalus.utils.Hex.toHex
import scalus.utils.await
import sttp.client4.*

import java.util.Base64
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

class PythClient(
    pythPolicyId: ScriptHash,
    pythKey: String,
    provider: BlockchainProvider
)(using backend: Backend[Future]) {

    private val lazerUrl = "https://pyth-lazer.dourolabs.app/v1/latest_price"

    /** Fetch signed price update bytes from Pyth Lazer REST API. */
    def fetchPriceUpdate(): ByteString = {
        val requestBody =
            s"""{"priceFeedIds":[16],"properties":["price"],"formats":["solana"],"channel":"fixed_rate@200ms"}"""
        val response = basicRequest
            .post(uri"$lazerUrl")
            .header("Authorization", s"Bearer $pythKey")
            .header("Content-Type", "application/json")
            .body(requestBody)
            .send(backend)
            .await(30.seconds)

        response.body match
            case Right(body) =>
                // Extract solana.data from JSON response
                // Response format: {"type":"streamUpdated","parsed":...,"solana":{"encoding":"hex"|"base64","data":"..."}}
                val encoding = extractSolanaEncoding(body)
                val data = extractSolanaData(body)
                encoding match
                    case "base64" => ByteString.fromArray(Base64.getDecoder.decode(data))
                    case _        => ByteString.fromHex(data)
            case Left(error) =>
                throw RuntimeException(s"Pyth Lazer API error: $error")
    }

    /** Find Pyth State UTxO by looking for the NFT with "Pyth State" token name. */
    def fetchPythState(): Utxo = {
        val pythStateName = AssetName(ByteString.fromString("Pyth State"))
        val utxos = provider
            .queryUtxos { u =>
                u.output.value.hasAsset(pythPolicyId, pythStateName)
            }
            .limit(1)
            .execute()
            .await(30.seconds)

        utxos match
            case Right(found) if found.nonEmpty => Utxo(found.head)
            case Right(_)   => throw RuntimeException("Pyth State UTxO not found")
            case Left(error) => throw RuntimeException(s"Failed to query Pyth State: $error")
    }

    /** Extract withdraw script hash from Pyth State inline datum (field 4). */
    def extractWithdrawScript(pythState: Utxo): ScriptHash = {
        import scalus.uplc.builtin.Data
        val datum: Data = pythState.output.requireInlineDatum
        val fields = datum.toConstr.snd
        // fields: governance, trusted_signers, deprecated_withdraw_scripts, withdraw_script
        val withdrawScriptBs = fields.tail.tail.tail.head.toByteString
        ScriptHash.fromHex(withdrawScriptBs.toHex)
    }

    /** Build the StakeAddress for the Pyth withdraw script. */
    def pythWithdrawAddress(network: Network): StakeAddress = {
        val pythState = fetchPythState()
        val withdrawHash = extractWithdrawScript(pythState)
        StakeAddress(network, StakePayload.Script(withdrawHash))
    }

    /** Parse price from update bytes for display (off-chain, mirrors on-chain logic). */
    def parsePrice(updateBytes: ByteString): BigDecimal = {
        import java.nio.{ByteBuffer, ByteOrder}
        val buf = ByteBuffer.wrap(updateBytes.bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Skip envelope: 4 magic + 64 sig + 32 key = 100 bytes, then 2 bytes payload size
        // Feed starts at offset 116
        val feedOffset = 116
        // Skip feed_id (4) + props_len (1) + prop_id (1) = 6 bytes
        val priceOffset = feedOffset + 6
        buf.position(priceOffset)
        val priceRaw = buf.getLong() // I64 LE
        BigDecimal(priceRaw) / BigDecimal(100_000_000L)
    }

    /** Extract the solana.encoding field from JSON response. */
    private def extractSolanaEncoding(json: String): String = {
        val key = "\"encoding\":\""
        val idx = json.lastIndexOf(key)
        if idx < 0 then return "hex" // default
        val start = idx + key.length
        val end = json.indexOf('"', start)
        if end < 0 then "hex" else json.substring(start, end)
    }

    /** Extract the solana.data field from JSON response. */
    private def extractSolanaData(json: String): String = {
        val dataKey = "\"data\":\""
        val idx = json.lastIndexOf(dataKey)
        if idx < 0 then throw RuntimeException(s"Cannot find solana.data in response: $json")
        val start = idx + dataKey.length
        val end = json.indexOf('"', start)
        if end < 0 then throw RuntimeException(s"Malformed solana.data in response: $json")
        json.substring(start, end)
    }
}
