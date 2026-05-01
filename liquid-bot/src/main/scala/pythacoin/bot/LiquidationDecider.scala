package pythacoin.bot

import pythacoin.CdpInfo

/** Pure decision logic. Mirrors the on-chain LTV check from
  * `pythacoin.onchain.CdpValidator.isLtvBelow`:
  *
  *   `LTV_below(threshold) := debt * 100 * ORACLE_SCALE <= threshold * collateral * price`
  *
  * The bot wants the inverse — it acts when the CDP is **at or above** the
  * threshold, i.e. when `isLtvBelow(threshold)` is false.
  *
  * Threshold is supplied in basis points to give the operator finer control than
  * the on-chain whole-percent threshold; the bot can choose to trigger slightly
  * before the on-chain validator would accept the tx (e.g. front-running for
  * safety) or stay conservative.
  */
object LiquidationDecider {

    /** Pyth ADA/USD has 8 decimal places (exponent = -8). Same constant as on-chain
      * `CdpValidator.ORACLE_SCALE`; replicated here so the decider has no
      * compile-time dependency on the validator object.
      */
    private val OracleScale: BigInt = BigInt(100_000_000)
    private val LtvScaleBps: BigInt = BigInt(10_000)

    enum Decision {
        case Skip(reason: String)
        case Liquidate(ltvBps: Int)
    }

    /** Decide whether `cdp` is liquidatable at `priceRaw` (the integer Pyth price,
      * e.g. 75_230_000 for $0.7523/ADA), given the operator's `minLtvBps` trigger,
      * the bot's available PUSD balance, and the minimum collateral the bot is
      * willing to accept after fees + minUtxo overhead.
      *
      * Note: `minProfitLovelace` is misnamed — it's a **minimum collateral
      * floor** (`cdp.collateralLovelace >= minProfitLovelace`), used as a
      * static proxy for `tx_fee + minUtxo`. Actual fees are only known after
      * `TxBuilder.complete`, so the operator picks a floor conservative enough
      * to avoid wasting fee budget on CDPs that wouldn't pay for their own
      * liquidation tx. The env-var name (`PYTHACOIN_MIN_PROFIT_LOVELACE`)
      * is preserved for backwards compatibility.
      */
    def decide(
        cdp: CdpInfo,
        priceRaw: BigInt,
        minLtvBps: Int,
        availablePusd: Long,
        minProfitLovelace: Long
    ): Decision = {
        if cdp.collateralLovelace <= 0 then Decision.Skip("zero collateral")
        else if priceRaw <= 0 then Decision.Skip("zero price")
        else if cdp.debtPusd <= 0 then Decision.Skip("zero debt")
        else
            val ltvBps = ltvBpsOf(cdp.collateralLovelace, cdp.debtPusd, priceRaw)
            if ltvBps < minLtvBps then Decision.Skip(s"healthy: $ltvBps bps < $minLtvBps bps")
            else if availablePusd < cdp.debtPusd then
                Decision.Skip(s"insufficient PUSD: have $availablePusd, need ${cdp.debtPusd}")
            else if cdp.collateralLovelace < minProfitLovelace then
                Decision.Skip(
                  s"unprofitable: collateral ${cdp.collateralLovelace} < min $minProfitLovelace"
                )
            else Decision.Liquidate(ltvBps)
    }

    /** LTV expressed in basis points. Uses BigInt so it never overflows; saturates
      * to `Int.MaxValue` if the ratio exceeds what `Int` can hold (extreme price
      * crash where collateral value is essentially zero).
      */
    def ltvBpsOf(collateralLovelace: Long, debtPusd: Long, priceRaw: BigInt): Int = {
        val numerator = BigInt(debtPusd) * LtvScaleBps * OracleScale
        val denominator = BigInt(collateralLovelace) * priceRaw
        if denominator.signum == 0 then Int.MaxValue
        else
            val q = numerator / denominator
            if q > BigInt(Int.MaxValue) then Int.MaxValue else q.toInt
    }
}
