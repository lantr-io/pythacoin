package pythacoin.bot

import org.scalatest.funsuite.AnyFunSuite
import scalus.uplc.builtin.ByteString

import java.time.Instant

class PriceCacheTest extends AnyFunSuite {

    private val Bytes = ByteString.fromArray(Array[Byte](1, 2, 3, 4))
    private val Raw   = BigInt(75_230_000L)

    test("returns None before anything is set") {
        val c = new PriceCache
        assert(c.current(Instant.ofEpochSecond(1000), 60).isEmpty)
    }

    test("returns the cached value while inside the freshness window") {
        val c = new PriceCache
        c.set(Bytes, Raw, Instant.ofEpochSecond(1000))
        val now = Instant.ofEpochSecond(1059) // 59s later, within 60s window
        val got = c.current(now, 60)
        assert(got.isDefined)
        assert(got.get.priceRaw == Raw)
        assert(got.get.updateBytes == Bytes)
    }

    test("treats a cached value exactly at the boundary as still fresh") {
        // Boundary is inclusive: now - fetchedAt <= maxAge ⇒ fresh.
        val c = new PriceCache
        c.set(Bytes, Raw, Instant.ofEpochSecond(1000))
        assert(c.current(Instant.ofEpochSecond(1060), 60).isDefined)
    }

    test("returns None once the value ages past maxAgeSeconds") {
        val c = new PriceCache
        c.set(Bytes, Raw, Instant.ofEpochSecond(1000))
        assert(c.current(Instant.ofEpochSecond(1061), 60).isEmpty)
    }

    test("set replaces the previous entry (latest write wins)") {
        val c = new PriceCache
        c.set(Bytes, Raw, Instant.ofEpochSecond(1000))
        val newBytes = ByteString.fromArray(Array[Byte](9, 9, 9))
        val newRaw   = BigInt(80_000_000L)
        c.set(newBytes, newRaw, Instant.ofEpochSecond(1100))
        val got = c.current(Instant.ofEpochSecond(1110), 60).get
        assert(got.priceRaw == newRaw)
        assert(got.updateBytes == newBytes)
    }
}
