package pythacoin.bot

import org.scalatest.funsuite.AnyFunSuite
import pythacoin.CdpInfo

class LiquidationDeciderTest extends AnyFunSuite {

    /** ADA/USD = $0.50, encoded with 8 decimals as the Pyth feed delivers. */
    private val price50c = BigInt(50_000_000)

    private def cdp(coll: Long, debt: Long): CdpInfo =
        CdpInfo("nft", "owner", coll, debt, 0.0)

    test("skips healthy CDP below threshold") {
        // 100 ADA collateral × $0.50 = $50 collateral value.
        // 40 PUSD debt → LTV = 40 / 50 = 80% = 8000 bps.
        val r = LiquidationDecider.decide(
          cdp(coll = 100_000_000L, debt = 40_000_000L),
          priceRaw = price50c,
          minLtvBps = 9000,
          availablePusd = Long.MaxValue,
          minProfitLovelace = 0L
        )
        assert(r.isInstanceOf[LiquidationDecider.Decision.Skip])
    }

    test("liquidates above threshold when bot has enough PUSD") {
        // 100 ADA × $0.50 = $50.  46 PUSD debt → LTV = 46/50 = 92% = 9200 bps.
        val r = LiquidationDecider.decide(
          cdp(coll = 100_000_000L, debt = 46_000_000L),
          priceRaw = price50c,
          minLtvBps = 9000,
          availablePusd = 100_000_000L,
          minProfitLovelace = 0L
        )
        r match
            case LiquidationDecider.Decision.Liquidate(bps) => assert(bps == 9200)
            case other => fail(s"expected Liquidate, got $other")
    }

    test("skips when PUSD balance is insufficient even if LTV is past threshold") {
        val r = LiquidationDecider.decide(
          cdp(coll = 100_000_000L, debt = 46_000_000L),
          priceRaw = price50c,
          minLtvBps = 9000,
          availablePusd = 10_000_000L,
          minProfitLovelace = 0L
        )
        r match
            case LiquidationDecider.Decision.Skip(reason) => assert(reason.contains("insufficient"))
            case other => fail(s"expected Skip, got $other")
    }

    test("skips zero collateral, zero debt, zero price") {
        def d(c: Long, debt: Long, price: BigInt) =
            LiquidationDecider.decide(
              cdp(c, debt),
              priceRaw = price,
              minLtvBps = 9000,
              availablePusd = 1L,
              minProfitLovelace = 0L
            )
        assert(d(0L, 1L, price50c).isInstanceOf[LiquidationDecider.Decision.Skip])
        assert(d(1L, 0L, price50c).isInstanceOf[LiquidationDecider.Decision.Skip])
        assert(d(1L, 1L, BigInt(0)).isInstanceOf[LiquidationDecider.Decision.Skip])
    }

    test("skips when collateral wouldn't cover the configured min-profit floor") {
        // Same scenario as the liquidate-success test, but with a 200-ADA minProfit
        // floor and only 100 ADA of collateral.
        val r = LiquidationDecider.decide(
          cdp(coll = 100_000_000L, debt = 46_000_000L),
          priceRaw = price50c,
          minLtvBps = 9000,
          availablePusd = 100_000_000L,
          minProfitLovelace = 200_000_000L
        )
        r match
            case LiquidationDecider.Decision.Skip(reason) =>
                assert(reason.contains("unprofitable"))
            case other => fail(s"expected Skip, got $other")
    }

    test("ltvBpsOf is monotonic in debt and inverse in price") {
        val a = LiquidationDecider.ltvBpsOf(100_000_000L, 40_000_000L, price50c)
        val b = LiquidationDecider.ltvBpsOf(100_000_000L, 50_000_000L, price50c)
        val c = LiquidationDecider.ltvBpsOf(100_000_000L, 50_000_000L, BigInt(40_000_000))
        assert(a < b)
        assert(c > b)
    }
}
