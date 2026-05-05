package pythacoin.integration

import org.scalatest.funsuite.AnyFunSuite
import pythacoin.{CardanoNet, PythClient, given}
import pythacoin.bot.{BotConfig, PriceCache, PriceStream, PythChannel}
import scalus.cardano.ledger.ScriptHash

import java.net.ServerSocket
import java.time.Instant
import scala.concurrent.duration.*

/** Drives the production [[PriceStream]] against an in-process
  * [[FakeLazerServer]] over a real WebSocket. Verifies:
  *
  *   - subscribe handshake completes,
  *   - `streamUpdated` JSON pushes drive `PriceCache.set` with the same raw
  *     I64 price the fake server published,
  *   - mid-stream price changes propagate within the WS push interval,
  *   - control / non-update frames (the subscribe ack) don't kill the session.
  *
  * Hermetic, no Docker, no external network — safe under default
  * `integration/test`.
  */
class PriceStreamTest extends AnyFunSuite {

    private def freePort(): Int = {
        val s = new ServerSocket(0)
        try s.getLocalPort finally s.close()
    }

    /** Construct a config with only the fields PriceStream actually reads. */
    private def configFor(wsUrl: String): BotConfig = BotConfig(
      cardanoNet = CardanoNet.Preprod,
      blockfrostApiKey = "",
      pythPolicyIdHex = "00" * 28,
      pythKey = "test-token",
      relayHost = "",
      relayPort = 0,
      appId = "test",
      walletAddrBech32 = "",
      signingKeyHex = "",
      verificationKeyHex = "",
      minLtvBps = 9000,
      minProfitLovelace = 0L,
      dryRun = true,
      pythWsUrl = wsUrl,
      pythChannel = PythChannel.FixedRate200ms,
      priceMaxAgeSeconds = 60L,
      chainStoreDir = None
    )

    /** PriceStream calls only `parsePriceRaw`, which is pure byte-parsing.
      * The other PythClient methods aren't reached, so the unused fields
      * (provider / Blockfrost) can stay null.
      */
    private def makePythClient(): PythClient = new PythClient(
      ScriptHash.fromHex("00" * 28), "", "", "", null
    )

    /** Spin until `cond` returns true or `timeout` elapses. */
    private def awaitWith(timeout: FiniteDuration)(cond: => Boolean): Boolean = {
        val deadline = System.nanoTime() + timeout.toNanos
        while System.nanoTime() < deadline do
            if cond then return true
            Thread.sleep(20)
        cond
    }

    test("PriceStream decodes streamUpdated pushes into PriceCache") {
        val httpPort = freePort()
        val wsPort = freePort()
        val initial = 75_230_000L // $0.7523
        val server = new FakeLazerServer(httpPort, wsPort, initial)
        server.start()
        try
            val cache = new PriceCache
            val cfg = configFor(s"ws://localhost:$wsPort")
            val stream = new PriceStream(cfg, makePythClient(), cache)

            val streamThread = new Thread(() => stream.run(), "price-stream-test")
            streamThread.setDaemon(true)
            streamThread.start()
            try
                // First push within ~200 ms (FakeLazer's default interval).
                assert(
                  awaitWith(10.seconds) {
                      cache.current(Instant.now(), 60).exists(_.priceRaw == BigInt(initial))
                  },
                  "PriceCache should receive initial price within 10s"
                )

                // Mid-stream change: server flips price, cache should follow.
                val updated = 31_500_000L // $0.315
                server.setPrice(updated)
                assert(
                  awaitWith(10.seconds) {
                      cache.current(Instant.now(), 60).exists(_.priceRaw == BigInt(updated))
                  },
                  "PriceCache should reflect the updated price after FakeLazer.setPrice"
                )
            finally
                streamThread.interrupt()
                streamThread.join(5_000)
        finally server.stop()
    }
}
