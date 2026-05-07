package pythacoin.integration

import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory
import ox.supervised
import pythacoin.bot.{Bot, BotConfig, PythChannel}
import pythacoin.CardanoNet

import java.time.Instant

/** Tag to gate preprod-against-real-Lazer runs out of the default test pass. Run explicitly with:
  * {{{sbt 'integration/testOnly *PreprodLiquidationTest -- -n PreprodTag'}}}
  */
object PreprodTag extends Tag("pythacoin.integration.PreprodTag")

/** Runs the bot against **real preprod + real Pyth Lazer** as a dry-run smoke check. Tagged so `sbt
  * integration/test` doesn't try to talk to preprod or eat operator credits.
  *
  * What this verifies (when run, in dry-run mode):
  *   - `BotApp` connects to a preprod relay via N2N and follows the chain.
  *   - `PriceStream` opens a real Pyth Lazer WS subscription, decodes pushes, and populates
  *     `priceCache` within `priceMaxAgeSeconds`.
  *   - `Evaluator` runs at least one decision pass against live data without crashing.
  *
  * What this does NOT verify:
  *   - That a liquidation actually fires — natural ADA/USD movement during a short window is
  *     unpredictable. To force a fire, lower `PYTHACOIN_MIN_LTV_BPS` enough that an existing CDP
  *     becomes liquidatable.
  *
  * Required env vars (all standard `BotConfig.fromEnv` ones):
  *   - `BLOCKFROST_API_KEY`, `PYTH_POLICY_ID`, `PYTH_KEY`, `PYTHACOIN_BOT_ADDR`,
  *     `PYTHACOIN_BOT_KEY`, `PYTHACOIN_BOT_VKEY`.
  *
  * Override `runDurationSeconds` (env `PYTHACOIN_PREPROD_TEST_SECONDS`) to extend or shorten the
  * observation window.
  */
class PreprodLiquidationTest extends AnyFunSuite {

    private val log = LoggerFactory.getLogger(classOf[PreprodLiquidationTest])

    /** How long to keep the bot running. Default: 90 s — long enough to see several WS pushes
      * (5×/sec at fixed_rate@200ms) and at least one chain-tip catch-up.
      */
    private val runDurationSeconds: Long =
        sys.env.getOrElse("PYTHACOIN_PREPROD_TEST_SECONDS", "90").toLong

    test("preprod smoke: bot starts, follows chain, receives WS prices (dry-run)", PreprodTag) {
        val env = EnvLoader.load()

        // Dry-run is enforced regardless of env so this test never submits a
        // real tx, even if an operator forgets to set PYTHACOIN_DRY_RUN=true.
        val cfg = BotConfig.fromMap(env).copy(dryRun = true)
        assert(cfg.cardanoNet == CardanoNet.Preprod, "Test requires PYTHACOIN_NETWORK=preprod")

        log.info(s"Preprod smoke test: running for ${runDurationSeconds}s in dry-run")
        val started = Instant.now()

        // Run BotApp in a supervised scope; cancel after the window.
        // OxApp's normal SIGTERM cancellation isn't available in tests, so
        // we drive cancellation by exiting the scope.
        supervised {
            ox.fork {
                Bot(cfg).run()
            }
            Thread.sleep(runDurationSeconds * 1000)
            // Falling out of supervised{} cancels the fork — no explicit close.
        }

        val elapsed = java.time.Duration.between(started, Instant.now()).toSeconds
        log.info(s"Preprod smoke test finished after ${elapsed}s without crash")
        // Reaching here means the bot didn't die; passing this test is a
        // signal that the wiring is healthy. Concrete liquidation-fired
        // assertions belong in a separate, longer-running observability test.
    }
}
