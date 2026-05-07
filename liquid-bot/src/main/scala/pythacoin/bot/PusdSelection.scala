package pythacoin.bot

import pythacoin.Assets
import scalus.cardano.ledger.{ScriptHash, TransactionInput, TransactionOutput, Utxos}

/** Pick the minimum number of wallet UTxOs whose PUSD content sums to at least the requested
  * amount.
  *
  * The bot ships every selected UTxO as an input to the liquidation tx, so a fragmented wallet
  * otherwise bloats the tx (more inputs ⇒ larger CBOR ⇒ higher fee, eventually past mempool tx-size
  * limits). Greedy-descending is not optimal in general (it can leave change > strictly necessary)
  * but is deterministic, allocation-light for the common single-input case, and reduces input count
  * from O(\|wallet\|) to typically 1–2.
  */
object PusdSelection {

    /** Select the smallest subset of `pusdUtxos` whose PUSD value is `>= needed`. Returns whatever
      * it could collect if the sum of all values is still less than `needed` — the decider already
      * gated on `availablePusd >= debt`, so that branch is unreachable in production; if hit, the
      * resulting tx will fail at submit and the bot logs a benign error.
      */
    def greedy(pusdUtxos: Utxos, policyId: ScriptHash, needed: Long): Utxos =
        greedyByAmount[(TransactionInput, TransactionOutput)](
          pusdUtxos,
          { case (_, o) => o.value.asset(policyId, Assets.Pusd) },
          needed
        ).toMap

    /** Generic greedy-descending picker. Pure — easy to unit-test without any scalus types.
      */
    private[bot] def greedyByAmount[A](
        items: Iterable[A],
        amount: A => Long,
        needed: Long
    ): List[A] = {
        if needed <= 0 then return Nil
        val sorted = items.toList.sortBy(a => -amount(a))
        val out = List.newBuilder[A]
        var accum = 0L
        val it = sorted.iterator
        while accum < needed && it.hasNext do
            val item = it.next()
            out += item
            accum += amount(item)
        out.result()
    }
}
