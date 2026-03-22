package pythacoin

import scalus.cardano.address.{Address, Network, StakeAddress, StakePayload}
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
    blockfrostApiKey: String,
    blockfrostBaseUrl: String,
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
        val asset = pythPolicyId.toHex + pythStateName.bytes.toHex

        // Step 1: find addresses holding this asset via Blockfrost
        val addrResponse = basicRequest
            .get(uri"$blockfrostBaseUrl/assets/$asset/addresses")
            .header("project_id", blockfrostApiKey)
            .send(backend)
            .await(30.seconds)

        val addrJson = addrResponse.body match
            case Right(body) => body
            case Left(error) => throw RuntimeException(s"Failed to find Pyth State asset: $error")

        // Extract first address from [{"address":"addr...","quantity":"1"}]
        val addrKey = "\"address\":\""
        val idx = addrJson.indexOf(addrKey)
        if idx < 0 then throw RuntimeException(s"No address found for Pyth State asset: $addrJson")
        val start = idx + addrKey.length
        val end = addrJson.indexOf('"', start)
        val addrBech32 = addrJson.substring(start, end)
        val addr = Address.fromBech32(addrBech32)

        // Step 2: query UTxOs at that address filtered by asset
        val utxos = provider.findUtxos(addr).await(30.seconds) match
            case Right(found) => found
            case Left(error)  => throw RuntimeException(s"Failed to query Pyth State UTxOs: $error")

        utxos.collectFirst {
            case (input, output) if output.value.hasAsset(pythPolicyId, pythStateName) =>
                Utxo(input, output)
        }.getOrElse(throw RuntimeException("Pyth State UTxO not found"))
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

    /** Fetch the Pyth withdraw PlutusScript from Blockfrost by script hash. */
    def fetchScript(scriptHash: ScriptHash): PlutusScript = {
        val hashHex = scriptHash.toHex
        val response = basicRequest
            .get(uri"$blockfrostBaseUrl/scripts/$hashHex/cbor")
            .header("project_id", blockfrostApiKey)
            .send(backend)
            .await(30.seconds)

        val json = response.body match
            case Right(body) => body
            case Left(error) => throw RuntimeException(s"Failed to fetch script $hashHex: $error")

        // Extract cbor field from {"cbor":"..."}
        val cborKey = "\"cbor\":\""
        val idx = json.indexOf(cborKey)
        if idx < 0 then throw RuntimeException(s"No cbor field in response: $json")
        val start = idx + cborKey.length
        val end = json.indexOf('"', start)
        val cborHex = json.substring(start, end)
        Script.PlutusV3(ByteString.fromHex(cborHex))
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
