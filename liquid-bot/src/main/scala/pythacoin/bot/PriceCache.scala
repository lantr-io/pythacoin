package pythacoin.bot

import scalus.uplc.builtin.ByteString

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Last Pyth price observed on the WS stream, with the wall-clock time it was received. The bot
  * reads this on every chain-event AND every WS push; the value is treated as `None` once it ages
  * past `maxAgeSeconds`, which lets a stale cache silently gate evaluation/submission instead of
  * feeding the validator a price update outside its ±600 s validity window.
  *
  * Thread-safe via `AtomicReference`. The reader path is lock-free; the writer (the WS callback) is
  * single-threaded by the Java HTTP client's listener dispatch but uses `set` for clarity, not
  * exclusion.
  */
final class PriceCache {
    import PriceCache.Cached

    private val ref = new AtomicReference[Option[Cached]](None)

    def set(updateBytes: ByteString, priceRaw: BigInt, fetchedAt: Instant): Unit =
        ref.set(Some(Cached(updateBytes, priceRaw, fetchedAt)))

    /** Latest cached price, or `None` if nothing has arrived yet OR the cached value has aged past
      * `maxAgeSeconds`. The aged-out check is read-side, not scheduled — there's no background
      * reaper, so there's no race with the WS writer.
      */
    def current(now: Instant, maxAgeSeconds: Long): Option[Cached] =
        ref.get() match
            case Some(c) if now.getEpochSecond - c.fetchedAt.getEpochSecond <= maxAgeSeconds =>
                Some(c)
            case _ => None
}

object PriceCache {
    final case class Cached(updateBytes: ByteString, priceRaw: BigInt, fetchedAt: Instant)
}
