package pythacoin

import scalus.uplc.builtin.ByteString

import java.util.Base64

/** Shared parsing for Pyth Lazer's JSON envelope around the signed Solana payload. Both transports
  * return the same shape:
  *
  * `{ ..., "solana": { "encoding": "base64" | "hex", "data": "<encoded>" }, ... }`
  *
  * REST returns it directly; WS wraps it inside a `streamUpdated` message.
  *
  * Hand-rolled scan instead of a JSON parser dep — the messages are small, the shape is stable, and
  * the bot's WS hot path runs at up to 5×/sec.
  */
object PythLazerEnvelope {

    /** Pull the `solana.data` and `solana.encoding` fields out of a Pyth Lazer JSON message and
      * return the decoded payload bytes. Returns `None` if the envelope isn't present (e.g. the WS
      * subscribe-ack frame).
      */
    def decode(json: String): Option[ByteString] = {
        val solanaIdx = json.indexOf("\"solana\"")
        if solanaIdx < 0 then return None
        for
            data <- extractField(json, solanaIdx, "data")
            encoding = extractField(json, solanaIdx, "encoding").getOrElse("hex")
        yield encoding match
            case "base64" => ByteString.fromArray(Base64.getDecoder.decode(data))
            case _        => ByteString.fromHex(data)
    }

    /** Look up `"<key>":"<value>"` starting from `from`. Returns the raw (still-encoded) value, or
      * `None` if absent / malformed.
      */
    private def extractField(json: String, from: Int, key: String): Option[String] = {
        val needle = "\"" + key + "\":\""
        val idx = json.indexOf(needle, from)
        if idx < 0 then None
        else
            val start = idx + needle.length
            val end = json.indexOf('"', start)
            if end < 0 then None else Some(json.substring(start, end))
    }
}
