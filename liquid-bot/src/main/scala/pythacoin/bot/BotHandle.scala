package pythacoin.bot

import pythacoin.CdpInfo
import scalus.cardano.ledger.Utxo

/** Observability handle into a running bot. Tests (and operators embedding
  * the bot in a larger process) use this instead of scraping logs.
  *
  * The accessors fall into two groups with different guarantees:
  *
  *   - Telemetry — `chainSnapshot()`, `evaluationsRun`,
  *     `liquidationCandidates`, `liquidationsAttempted`,
  *     `liquidationsSubmitted` — read atomic state and never block. Safe to
  *     poll from any thread.
  *
  *   - `ctx` — the live [[BotCtx]] the bot is running with. Useful to get at
  *     `policyId`, `scriptAddr`, the `AppCtx`, queries, tx-builders, etc.
  *     Calling into the providers/clients hung off `ctx.appCtx` (Blockfrost
  *     queries, tx submission) IS blocking I/O — treat those as you would in
  *     production, not as cheap snapshot reads.
  *
  * The handle is valid only for the lifetime of the supervised scope that
  * created it; using it after the scope unwinds is undefined.
  */
trait BotHandle {

    /** The live `BotCtx` the bot is running with. Useful to get at `policyId`,
      * `scriptAddr`, the `AppCtx`, etc. without re-deriving them in the test.
      *
      * NB: methods that go through `ctx.appCtx.provider` /
      * `ctx.appCtx.cdpQueries` / `ctx.streamProvider` are real I/O and may
      * block. The non-blocking guarantee on this trait covers only the
      * telemetry accessors below.
      */
    def ctx: BotCtx

    /** Atomic snapshot of the chain follower's CDP view, or `None` while the
      * view is known-inconsistent (a rollback whose reseed hasn't yet
      * succeeded). Non-blocking.
      */
    def chainSnapshot(): Option[Iterable[(Utxo, CdpInfo)]]

    /** Total decision passes that actually ran (i.e. weren't dropped by the
      * busy gate, the empty-cdps short-circuit, or a stale price cache).
      * Atomic read.
      */
    def evaluationsRun: Long

    /** Total CDPs flagged as Liquidate by the decider. Atomic read. */
    def liquidationCandidates: Long

    /** Total liquidation tx attempts. Atomic read. */
    def liquidationsAttempted: Long

    /** Total liquidations the chain accepted. Atomic read. */
    def liquidationsSubmitted: Long
}
