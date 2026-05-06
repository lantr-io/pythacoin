package pythacoin.bot

import pythacoin.CdpInfo
import scalus.cardano.ledger.{TransactionInput, Utxo}

/** Observability handle into a running bot — used by integration tests instead of scraping logs.
  * Valid only for the lifetime of the supervised scope that created it.
  *
  * The telemetry accessors below (`chainSnapshot`, `evaluationsRun`, etc.) are non-blocking atomic
  * reads. `ctx` exposes the live [[BotCtx]] for tests that need the queries / tx-builders /
  * providers; calls into those are real I/O and may block.
  */
final class BotHandle private[bot] (
    val ctx: BotCtx,
    follower: ChainFollower,
    evaluator: Evaluator
) {

    /** Atomic snapshot of the chain follower's CDP view, keyed by the CDP UTxO's
      * [[TransactionInput]]. Returns `None` while a rollback's reseed hasn't yet succeeded.
      */
    def chainSnapshot(): Option[collection.Map[TransactionInput, (Utxo, CdpInfo)]] =
        follower.snapshot()

    /** Decision passes that actually ran (cache available, slot acquired). */
    def evaluationsRun: Long = evaluator.evaluationsRun

    /** CDPs flagged as liquidatable by the decider. */
    def liquidationCandidates: Long = evaluator.liquidationCandidates

    /** Liquidation tx attempts (counts every `submitLiquidation` entry). */
    def liquidationsAttempted: Long = evaluator.liquidationsAttempted

    /** Liquidations the chain accepted. */
    def liquidationsSubmitted: Long = evaluator.liquidationsSubmitted
}
