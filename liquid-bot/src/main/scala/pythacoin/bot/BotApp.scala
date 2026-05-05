package pythacoin.bot

import com.monovore.decline.{Command, Opts}
import org.slf4j.LoggerFactory
import ox.{ExitCode, Ox, OxApp, fork, useCloseableInScope}
import pythacoin.CdpInfo
import scalus.cardano.ledger.Utxo

import java.time.Instant

/** Entry point for the liquidation bot. Two subcommands:
  *   - `dry-run`  — connect, follow the chain, log candidate liquidations, never submit.
  *   - `start`    — same, but submit liquidation transactions for under-collateralised CDPs.
  *
  * `start` honours `PYTHACOIN_DRY_RUN=true` as an extra safety net.
  *
  * Extending `OxApp` gives us a `supervised` scope around `run` (so
  * `useCloseableInScope` works), a JVM shutdown hook that cancels the main fork
  * on SIGINT/SIGTERM, and configurable exception callbacks via `OxApp.Settings`.
  */
object BotApp extends OxApp {

    private val log = LoggerFactory.getLogger(BotApp.getClass)

    private val command: Command[Boolean] = {
        val dryRun = Opts.subcommand("dry-run", "Follow the chain and log candidate liquidations") {
            Opts(true)
        }
        val start = Opts.subcommand("start", "Run the liquidation bot") {
            Opts(false)
        }
        Command(name = "pythacoin-bot", header = "Pythacoin liquidation keeper")(dryRun orElse start)
    }

    override def run(args: Vector[String])(using Ox): ExitCode =
        command.parse(args) match
            case Left(help) =>
                println(help); ExitCode.Failure(2)
            case Right(forceDryRun) =>
                val envCfg = BotConfig.fromEnv()
                val cfg = envCfg.copy(dryRun = forceDryRun || envCfg.dryRun)
                runWithConfig(cfg); ExitCode.Success

    /** Bot lifecycle given an already-built config. Public so tests can drive
      * it with an in-code `BotConfig` instead of going through env vars.
      *
      * Three concurrent triggers feed `Evaluator.tryEvaluate`:
      *   - chain events (CDP set changed),
      *   - WS price pushes (price moved; same CDP set may now be liquidatable),
      *   - the priceLoop poll that bridges the WS write-side to the read-side
      *     and doubles as the retry mechanism for Reseeded events that get
      *     dropped by the busy gate.
      */
    def runWithConfig(cfg: BotConfig)(using Ox): Unit = {
        val ctx = useCloseableInScope(BotCtx(cfg))
        log.info(s"Starting\n${ctx.show}")
        val evaluator = new Evaluator(ctx)
        fork {
            new PriceStream(cfg, ctx.appCtx.pythClient, ctx.priceCache).run()
        }
        val follower = new ChainFollower(ctx, onCdpChange(evaluator))
        fork {
            priceLoop(ctx, evaluator, () => follower.snapshot())
        }
        follower.runForever()
    }

    /** Per-event dispatch: scope the work to what actually changed.
      *   - `Added`    → re-price just that one CDP,
      *   - `Removed`  → no work (the CDP is gone),
      *   - `Reseeded` → rollback path; mark the view dirty so a dropped pass
      *                  is retried by the priceLoop tick.
      */
    private def onCdpChange(evaluator: Evaluator)(event: CdpEvent): Unit = event match
        case CdpEvent.Added(utxo, info) => evaluator.tryEvaluate(Iterable((utxo, info)))
        case CdpEvent.Removed(_)        => ()
        case CdpEvent.Reseeded(all)     =>
            evaluator.markViewDirty()
            if evaluator.tryEvaluate(all) then evaluator.clearViewDirty()

    /** Poll the WS-backed cache and re-evaluate the live CDP view whenever
      * a fresh push arrives or `viewDirty` is set. The 50 ms tick is tighter
      * than the WS channel rate (200 ms) so we don't add latency on top of
      * the push; the skip-if-busy gate inside `tryEvaluate` prevents
      * back-pressure from in-flight passes.
      *
      * `snapshot()` returns `None` while the chain follower's view is known
      * to be inconsistent (a failed rollback reseed). We hold off evaluation
      * in that window so a stale post-fork view can't drive a fee-burning
      * submission; the next successful reseed restores it.
      */
    private def priceLoop(
        ctx: BotCtx,
        evaluator: Evaluator,
        snapshot: () => Option[Iterable[(Utxo, CdpInfo)]]
    ): Unit = {
        var lastFetchedAt: Option[Instant] = None
        while !Thread.currentThread.isInterrupted do
            ctx.priceCache.current(Instant.now(), ctx.cfg.priceMaxAgeSeconds).foreach { c =>
                snapshot() match
                    case None =>
                        // Inconsistent view; defer evaluation until reseed succeeds.
                        ()
                    case Some(view) =>
                        val newPrice = !lastFetchedAt.contains(c.fetchedAt)
                        val newView  = evaluator.isViewDirty
                        if (newPrice || newView) && evaluator.tryEvaluate(view) then
                            lastFetchedAt = Some(c.fetchedAt)
                            if newView then evaluator.clearViewDirty()
            }
            try Thread.sleep(50L)
            catch case _: InterruptedException =>
                Thread.currentThread.interrupt(); return
    }
}
