package pythacoin.bot

import pythacoin.CdpInfo
import scalus.cardano.ledger.Utxo

/** Read-only observability handle into a running bot. Tests (and operators
  * embedding the bot in a larger process) use this instead of scraping logs.
  *
  * All accessors are thread-safe and non-blocking — they return whatever the
  * bot's atomic state is at the moment of the call.
  */
trait BotHandle {

    /** The same `BotCtx` the bot is running with. Useful to get at `policyId`,
      * `scriptAddr`, the `AppCtx`, etc. without re-deriving them in the test.
      */
    def ctx: BotCtx

    /** Snapshot of the chain follower's CDP view, or `None` while the view is
      * known-inconsistent (a rollback whose reseed hasn't yet succeeded).
      */
    def chainSnapshot(): Option[Iterable[(Utxo, CdpInfo)]]

    /** Total successful evaluation passes. */
    def evaluationsRun: Long

    /** Total CDPs flagged as Liquidate by the decider. */
    def liquidationCandidates: Long

    /** Total liquidation tx attempts. */
    def liquidationsAttempted: Long

    /** Total liquidations the chain accepted. */
    def liquidationsSubmitted: Long
}
