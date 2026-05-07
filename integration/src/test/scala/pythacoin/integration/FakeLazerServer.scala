package pythacoin.integration

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/** In-process Pyth Lazer stand-in — REST endpoint at `POST /v1/latest_price` and a WS server at
  * `/v1/stream` on a separate port. Both serve the same synthetic Solana-format payload,
  * regenerated from `currentPriceRaw`.
  *
  * Drive the test by calling `setPrice(rawI64)`. WS subscribers get a fresh push within
  * `pushIntervalMs`. REST callers always get the latest value.
  *
  * The payload's signature/key/magic bytes are zeros — the test deploys `PlutusV3.alwaysOk` as the
  * Pyth withdraw script, so on-chain phase-2 never actually verifies the signature. The bot's
  * `parsePriceRaw` reads the price at offset 116+6 (LE I64); only that field needs to be correct.
  */
final class FakeLazerServer(
    httpPort: Int,
    wsPort: Int,
    initialPriceRaw: Long,
    pushIntervalMs: Long = 200L
) {
    import FakeLazerServer.*

    private val log = LoggerFactory.getLogger(classOf[FakeLazerServer])

    private val priceRaw = new AtomicLong(initialPriceRaw)

    /** Latest synthetic payload bytes, base64-encoded — recomputed on every `setPrice`. */
    @volatile private var payloadBase64: String = encodePayloadBase64(initialPriceRaw)

    /** Set a new price (raw 8-decimal LE I64, e.g. 75_230_000 for $0.7523/ADA). */
    def setPrice(raw: Long): Unit = {
        priceRaw.set(raw)
        payloadBase64 = encodePayloadBase64(raw)
    }

    private val httpServer: HttpServer = {
        val s = HttpServer.create(new InetSocketAddress(httpPort), 0)
        s.createContext(
          "/v1/latest_price",
          (ex: HttpExchange) => {
              try
                  val body = restResponseJson(payloadBase64).getBytes(StandardCharsets.UTF_8)
                  ex.getResponseHeaders.add("Content-Type", "application/json")
                  ex.sendResponseHeaders(200, body.length)
                  val os = ex.getResponseBody
                  try os.write(body)
                  finally os.close()
              catch
                  case e: Exception =>
                      log.warn(s"FakeLazer REST error: ${e.getMessage}")
          }
        )
        s.setExecutor(null)
        s
    }

    private val wsClients = ConcurrentHashMap.newKeySet[WebSocket]()

    private val wsServer: WebSocketServer = new WebSocketServer(new InetSocketAddress(wsPort)) {
        override def onOpen(conn: WebSocket, handshake: ClientHandshake): Unit = {
            log.info(s"FakeLazer WS open from ${conn.getRemoteSocketAddress}")
            wsClients.add(conn)
        }
        override def onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean): Unit = {
            wsClients.remove(conn)
            log.info(s"FakeLazer WS close: code=$code reason='$reason'")
        }
        override def onMessage(conn: WebSocket, message: String): Unit = {
            // Reply once with subscribe-ack on subscribe, then the push thread
            // streams updates. Real Lazer sends one ack then continuous pushes.
            if message.contains("\"subscribe\"") then
                conn.send("""{"type":"subscribed","subscriptionId":1}""")
        }
        override def onError(conn: WebSocket, ex: Exception): Unit =
            log.warn(s"FakeLazer WS error: ${ex.getMessage}")
        override def onStart(): Unit =
            log.info(s"FakeLazer WS server started on port $wsPort")
    }

    private val pushThread: Thread = {
        val t = new Thread(
          () => {
              while !Thread.currentThread.isInterrupted do
                  try
                      val msg = wsPushJson(payloadBase64)
                      wsClients.asScala.foreach { c =>
                          try if c.isOpen then c.send(msg)
                          catch case _: Exception => ()
                      }
                      Thread.sleep(pushIntervalMs)
                  catch
                      case _: InterruptedException =>
                          Thread.currentThread.interrupt()
          },
          "fake-lazer-push"
        )
        t.setDaemon(true)
        t
    }

    def start(): Unit = {
        httpServer.start()
        wsServer.start()
        pushThread.start()
        log.info(s"FakeLazerServer started: REST=:$httpPort, WS=:$wsPort")
    }

    def stop(): Unit = {
        pushThread.interrupt()
        try wsServer.stop(500)
        catch case _: Exception => ()
        try httpServer.stop(0)
        catch case _: Exception => ()
    }

    def httpUrl: String = s"http://localhost:$httpPort/v1/latest_price"
    def wsUrl: String = s"ws://localhost:$wsPort"
}

object FakeLazerServer {

    /** Synthetic Solana-format payload — zero-filled envelope + payload header with a single
      * ADA/USD feed carrying `priceRaw` as an I64 LE.
      *
      * Layout (matches `CdpValidator.parsePythPrice`): [4 magic][64 sig][32 signer key][2
      * payload_size] ← envelope, 102 bytes [4 magic][8 timestamp_us][1 channel_id][1 feeds_len] ←
      * payload header, 14 bytes (offset 102) [4 feed_id (=16 ADA/USD)][1 props_len (=1)][1 prop_id
      * (=0 Price)][8 price I64 LE] ← feed (offset 116)
      */
    private val AdaUsdFeedId: Int = 16

    /** Build the synthetic Solana-format payload for a given raw price. Exposed so a test that
      * wants to drive a single tx (without subscribing to the WS push stream) can synthesize the
      * cached-bytes input directly.
      */
    def buildPayload(priceRaw: Long): Array[Byte] = {
        val total = 102 + 14 + 4 + 1 + 1 + 8 // 130 bytes
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        // envelope: zeros for magic/sig/key (alwaysOk script doesn't verify)
        buf.position(100)
        buf.putShort((total - 102).toShort) // payload_size
        // payload header: zeros for magic/timestamp/channel, 1 feed
        buf.position(102 + 13)
        buf.put(1.toByte) // feeds_len
        // feed
        buf.putInt(AdaUsdFeedId)
        buf.put(1.toByte) // props_len
        buf.put(0.toByte) // prop_id = Price
        buf.putLong(priceRaw)
        buf.array()
    }

    def encodePayloadBase64(priceRaw: Long): String =
        Base64.getEncoder.encodeToString(buildPayload(priceRaw))

    /** REST response shape: matches what `PythLazerEnvelope.decode` expects. */
    private def restResponseJson(b64: String): String =
        s"""{"solana":{"encoding":"base64","data":"$b64"}}"""

    /** WS push shape: `streamUpdated` envelope wrapping the same `solana` block. */
    private def wsPushJson(b64: String): String =
        s"""{"type":"streamUpdated","subscriptionId":1,"solana":{"encoding":"base64","data":"$b64"}}"""
}
