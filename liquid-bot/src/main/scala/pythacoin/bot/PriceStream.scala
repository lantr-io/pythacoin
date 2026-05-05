package pythacoin.bot

import org.slf4j.LoggerFactory
import pythacoin.{PythClient, PythLazerEnvelope}

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.ByteBuffer
import java.time.{Duration as JDuration, Instant}
import java.util.concurrent.{CompletionStage, CountDownLatch}

/** Persistent WebSocket subscription to Pyth Lazer that pushes signed price
  * updates into `cache`. Designed to live in a single forked Ox fiber alongside
  * the chain follower; cancellation aborts the in-flight WS.
  *
  *   - Subscribe message: `{"type":"subscribe", ..., "formats":["solana"], "jsonBinaryEncoding":"base64"}`
  *   - Push message:      `{"type":"streamUpdated", "solana":{"encoding":"base64","data":"..."}}`
  *
  * The base64 `solana.data` is byte-identical to the REST `/v1/latest_price`
  * payload, so `PythClient.parsePriceRaw` decodes it unchanged.
  *
  * Reconnect is exponential-backoff (1s → 30s, capped). The cache ages out on
  * its own (`PriceCache.current` checks wall-clock), so during a long
  * disconnect `BotApp.tryEvaluate` cleanly skips passes — no separate "we are
  * disconnected" flag is needed in the read path.
  */
final class PriceStream(
    cfg: BotConfig,
    pythClient: PythClient,
    cache: PriceCache
) {

    private val log = LoggerFactory.getLogger(classOf[PriceStream])

    private val httpClient = HttpClient.newHttpClient()
    // Single-threaded write/read (only the run loop touches it), so a plain
    // var is enough; the cancellation path runs on the same thread that wrote.
    private var activeWs: WebSocket = null

    private val MaxBackoffMs = 30_000L
    // Defensive guard against a server that never sets last=true on a frame;
    // 1 MiB is two orders of magnitude above any plausible push.
    private val MaxPartialFrameBytes = 1_048_576

    /** Connect → subscribe → drive the push loop forever. Returns only when
      * the calling fiber is cancelled (interrupted). */
    def run(): Unit = {
        var backoffMs = 1_000L
        while !Thread.currentThread.isInterrupted do
            try
                val closed = openOnce()
                closed.await()             // blocks until the WS closes or errors
                backoffMs = 1_000L         // any successful session resets backoff
            catch
                case _: InterruptedException =>
                    Thread.currentThread.interrupt()
                    abortActive()
                    return
                case e: Exception =>
                    log.warn(s"PriceStream session failed: ${e.getMessage}")

            if Thread.currentThread.isInterrupted then return
            log.info(s"PriceStream reconnecting in ${backoffMs} ms")
            try Thread.sleep(backoffMs)
            catch case _: InterruptedException =>
                Thread.currentThread.interrupt(); return
            backoffMs = math.min(backoffMs * 2, MaxBackoffMs)
    }

    /** Open a single WS session, send the subscribe message, install the
      * frame listener. Returns a latch that fires when the session ends.
      */
    private def openOnce(): CountDownLatch = {
        val closedLatch = new CountDownLatch(1)
        val listener = new FrameListener(closedLatch)
        val ws = httpClient.newWebSocketBuilder()
            .header("Authorization", s"Bearer ${cfg.pythKey}")
            .connectTimeout(JDuration.ofSeconds(15))
            .buildAsync(URI.create(cfg.pythWsUrl), listener)
            .join()
        activeWs = ws
        log.info(s"PriceStream connected to ${cfg.pythWsUrl}, channel=${cfg.pythChannel.wireName}")
        // priceFeedId 16 == ADA/USD on Pyth Lazer, same as the REST request body.
        val subscribe =
            s"""{"type":"subscribe","subscriptionId":1,"priceFeedIds":[16],"properties":["price"],"formats":["solana"],"deliveryFormat":"json","jsonBinaryEncoding":"base64","parsed":false,"channel":"${cfg.pythChannel.wireName}"}"""
        ws.sendText(subscribe, true).join()
        closedLatch
    }

    private def abortActive(): Unit = {
        val ws = activeWs
        if ws != null then
            activeWs = null
            try ws.abort() catch case _: Throwable => ()
    }

    /** Java WebSocket listener. Single-threaded (the JDK dispatches frames
      * sequentially per session), so the StringBuilder doesn't need locking.
      */
    private final class FrameListener(closedLatch: CountDownLatch) extends WebSocket.Listener {
        private val partial = new StringBuilder

        override def onOpen(ws: WebSocket): Unit = {
            ws.request(1)
        }

        override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] = {
            partial.append(data)
            if partial.length > MaxPartialFrameBytes then
                log.warn(s"PriceStream partial frame exceeded ${MaxPartialFrameBytes} bytes; dropping")
                partial.setLength(0)
            else if last then
                val msg = partial.toString
                partial.setLength(0)
                handleMessage(msg)
            ws.request(1)
            null
        }

        override def onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage[?] = {
            // Subscribed with deliveryFormat=json, but be defensive against
            // server-side protocol changes — drop binary frames silently.
            ws.request(1)
            null
        }

        override def onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage[?] = {
            log.info(s"PriceStream WS closed: status=$statusCode reason='$reason'")
            closedLatch.countDown()
            null
        }

        override def onError(ws: WebSocket, error: Throwable): Unit = {
            log.warn(s"PriceStream WS error: ${error.getMessage}")
            closedLatch.countDown()
        }
    }

    /** Parse one text frame. Drop any frame that isn't a `streamUpdated` push
      * (the subscribe ack and any future control messages are ignored).
      */
    private def handleMessage(msg: String): Unit = {
        try
            // Cheap type discriminator — avoid pulling a JSON parser for one field.
            if !msg.contains("\"streamUpdated\"") then
                if msg.contains("\"subscribed\"") then log.info(s"PriceStream subscribed: $msg")
                else log.debug(s"PriceStream non-update frame: $msg")
                return
            PythLazerEnvelope.decode(msg) match
                case Some(bytes) =>
                    val raw = BigInt(pythClient.parsePriceRaw(bytes))
                    cache.set(bytes, raw, Instant.now())
                case None =>
                    log.debug(s"PriceStream streamUpdated without solana payload: $msg")
        catch case e: Exception =>
            // A malformed push must not kill the session — drop the frame and keep going.
            log.warn(s"PriceStream message decode failed: ${e.getMessage}")
    }
}
