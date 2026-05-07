package pythacoin.bot

import org.slf4j.LoggerFactory
import ox.{Ox, fork, useCloseableInScope}

import java.time.Instant

/** A liquidation bot instance.
  *
  * Construction allocates the live components — `BotCtx`, `Evaluator`, `CdpSource` — and registers
  * `ctx` with the enclosing `Ox` scope so it's closed when the scope tears down. No threads are
  * forked at construction time; that happens in [[run]], which is the explicit "go" verb.
  *
  * Tests construct `Bot(cfg)` inside a `supervised` scope, fork `run()` to drive the chain, and
  * read `cdps.snapshot()`, `evaluator.evaluationsRun`, `ctx.priceCache`, etc. directly for
  * assertions — no observability shim needed.
  */
final class Bot(cfg: BotConfig)(using Ox) {

    private val log = LoggerFactory.getLogger(classOf[Bot])

    val ctx: BotCtx = useCloseableInScope(BotCtx(cfg))
    val evaluator: Evaluator = new Evaluator(ctx)
    val cdps: CdpSource = new CdpSource(ctx, onCdpChange)

    /** Fork the price-stream WS pump and the price-loop poll, then block on the chain follower
      * until the supervised scope is cancelled.
      *
      * Three concurrent triggers feed `Evaluator.tryEvaluate`:
      *   - chain events (CDP set changed),
      *   - WS price pushes (price moved; same CDP set may now be liquidatable),
      *   - the priceLoop poll that bridges the WS write-side to the read-side and doubles as the
      *     retry mechanism for Reseeded events that get dropped by the busy gate.
      */
    def run(): Unit = {
        log.info(s"Starting\n${ctx.show}")
        fork {
            new PriceStream(cfg, ctx.appCtx.pythClient, ctx.priceCache).run()
        }
        fork {
            priceLoop()
        }
        cdps.run()
    }

    /** Per-event dispatch: scope the work to what actually changed.
      *   - `Added` → re-price just that one CDP,
      *   - `Removed` → no work (the CDP is gone),
      *   - `Reseeded` → rollback path; mark the view dirty so a dropped pass is retried by the
      *     priceLoop tick.
      */
    private def onCdpChange(event: CdpEvent): Unit = event match
        case CdpEvent.Added(utxo, info) => evaluator.tryEvaluate(Iterable((utxo, info)))
        case CdpEvent.Removed(_)        => ()
        case CdpEvent.Reseeded(all) =>
            evaluator.markViewDirty()
            if evaluator.tryEvaluate(all.values) then evaluator.clearViewDirty()

    /** Poll the WS-backed cache and re-evaluate the live CDP view whenever a fresh push arrives or
      * `viewDirty` is set. The 50 ms tick is tighter than the WS channel rate (200 ms) so we don't
      * add latency on top of the push; the skip-if-busy gate inside `tryEvaluate` prevents
      * back-pressure from in-flight passes.
      *
      * `cdps.snapshot()` returns `None` while the source's view is known to be inconsistent (a
      * failed rollback reseed). We hold off evaluation in that window so a stale post-fork view
      * can't drive a fee-burning submission; the next successful reseed restores it.
      */
    private def priceLoop(): Unit = {
        var lastFetchedAt: Option[Instant] = None
        while !Thread.currentThread.isInterrupted do
            ctx.priceCache.current(Instant.now(), ctx.cfg.priceMaxAgeSeconds).foreach { c =>
                cdps.snapshot() match
                    case None =>
                        // Inconsistent view; defer evaluation until reseed succeeds.
                        ()
                    case Some(view) =>
                        val newPrice = !lastFetchedAt.contains(c.fetchedAt)
                        val newView = evaluator.isViewDirty
                        if (newPrice || newView) && evaluator.tryEvaluate(view.values) then
                            lastFetchedAt = Some(c.fetchedAt)
                            if newView then evaluator.clearViewDirty()
            }
            try Thread.sleep(50L)
            catch
                case _: InterruptedException =>
                    Thread.currentThread.interrupt(); return
    }
}
