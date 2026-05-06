package pythacoin.integration

import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory
import ox.{fork, supervised}
import pythacoin.bot.{BotApp, BotConfig, BotHandle, Wallet}
import pythacoin.{AppCtx, CardanoNet}
import scalus.cardano.address.{Address, ShelleyAddress, ShelleyPaymentPart}
import scalus.cardano.ledger.{AddrKeyHash, AssetName}
import scalus.cardano.onchain.plutus.v1.PubKeyHash
import scalus.uplc.builtin.ByteString
import scalus.utils.await

import java.time.Instant
import scala.concurrent.duration.*
import scala.io.Source

/** End-to-end smoke test against preprod or preview, exercising the full bot
  * stack with a real ChainStore-backed warm restart, real Pyth Lazer WS, and
  * a deterministic test CDP that's idempotent across runs.
  *
  * See `liquid-bot/doc/end-to-end-test.md` for the design. The hooks this
  * test relies on (BotHandle, runWithConfig observe overload, Mithril
  * bootstrap on empty store) all live in main; this file is the assertion
  * harness.
  *
  * Tagged `PreprodTag` so `sbt integration/test` skips it by default. Run with:
  * {{{
  *   sbt 'integration/testOnly *PreprodEndToEndTest -- -n pythacoin.integration.PreprodTag'
  * }}}
  *
  * Required env (in `.env` or `sys.env`; sys.env wins on conflict):
  *   - `PYTHACOIN_NETWORK` — `preprod` or `preview`.
  *   - Standard `BotConfig.fromEnv` vars: `BLOCKFROST_API_KEY`, `PYTH_POLICY_ID`,
  *     `PYTH_KEY`, `PYTHACOIN_BOT_ADDR`, `PYTHACOIN_BOT_KEY`, `PYTHACOIN_BOT_VKEY`.
  *   - `PYTHACOIN_CHAIN_STORE_DIR` — persistent RocksDB path.
  *
  * Optional:
  *   - `PYTHACOIN_E2E_DURATION_SECONDS` — run window (default 180).
  *   - `PYTHACOIN_E2E_CLEANUP` — `true` closes the test CDP on success
  *     (default leaves it on chain so the next run is faster).
  *   - `PYTHACOIN_BOOTSTRAP=mithril` (+ `PYTHACOIN_MITHRIL_*`) — fast first
  *     run via a Mithril snapshot rather than full sync from genesis.
  */
class PreprodEndToEndTest extends AnyFunSuite {

    private val log = LoggerFactory.getLogger(classOf[PreprodEndToEndTest])

    /** AssetName max is 32 bytes. 4-byte prefix + 28-byte AddrKeyHash fits
      * exactly, keeping the full PKH for per-wallet uniqueness.
      */
    private val NftPrefix: Array[Byte] = "e2e:".getBytes("UTF-8")

    /** Bot defaults `priceMaxAgeSeconds` to 60; 50 ADA / 5 PUSD opens at
      * any plausible ADA/USD price (LTV ~10% even at $0.05/ADA), so the
      * health margin is wide enough that natural price movement during the
      * test window can't make this CDP a liquidation candidate.
      */
    private val TestCollateralLovelace = 50_000_000L
    private val TestDebtMicroPusd      = 5_000_000L

    test("preprod e2e: warm-restart + real WS + deterministic CDP discovered", PreprodTag) {
        val env = loadEnv()
        val cfg = BotConfig.fromMap(env).copy(dryRun = true)

        require(
          cfg.cardanoNet == CardanoNet.Preprod || cfg.cardanoNet == CardanoNet.Preview,
          s"Test requires PYTHACOIN_NETWORK=preprod|preview; got ${cfg.cardanoNet}"
        )
        require(
          cfg.chainStoreDir.isDefined,
          "Test requires PYTHACOIN_CHAIN_STORE_DIR — the warm-restart semantic " +
              "is the whole point of the e2e harness"
        )

        val durationSeconds = env.getOrElse("PYTHACOIN_E2E_DURATION_SECONDS", "180").toLong
        val cleanup         = env.getOrElse("PYTHACOIN_E2E_CLEANUP", "false").toBoolean

        // Build a side AppCtx + Wallet for setup tx-building. The bot will
        // construct its own AppCtx internally; the two share Blockfrost +
        // Pyth credentials so they see the same chain state.
        val appCtx = AppCtx(cfg.cardanoNet, cfg.blockfrostApiKey, cfg.pythPolicyIdHex, cfg.pythKey)
        val wallet = Wallet.fromConfig(cfg)
        val pkh    = paymentKeyHash(wallet.address)
        val nftAsset = deterministicNft(pkh)
        val nftHex   = nftAsset.bytes.toHex

        log.info(
          s"E2E run: network=${cfg.cardanoNet} chainStore=${cfg.chainStoreDir.get} " +
              s"wallet=${renderAddress(wallet.address)} testCdpNft=$nftHex"
        )
        ensureTestCdp(appCtx, wallet, nftAsset, pkh)

        val started = Instant.now()
        runBotAndAssert(cfg, nftHex, durationSeconds)
        val elapsed = java.time.Duration.between(started, Instant.now()).toSeconds
        log.info(s"E2E observation window finished after ${elapsed}s, all assertions passed")

        if cleanup then closeTestCdp(appCtx, wallet, nftHex)
        else log.info(s"Leaving test CDP $nftHex on chain (PYTHACOIN_E2E_CLEANUP=false)")
    }

    /** Spin up the bot in a supervised scope, capture its [[BotHandle]] via
      * the `observe` callback, poll the three forward-progress assertions,
      * and exit the scope as soon as they all pass (or the deadline fires).
      *
      * Falling out of `supervised` cancels the bot fork; the fork's chain
      * follower is interrupt-aware, so this is the clean shutdown path.
      */
    private def runBotAndAssert(cfg: BotConfig, nftHex: String, durationSeconds: Long): Unit = {
        @volatile var captured: Option[BotHandle] = None
        supervised {
            fork {
                BotApp.runWithConfig(cfg, observe = h => captured = Some(h))
            }
            val deadlineMs = System.currentTimeMillis() + durationSeconds * 1000

            val handle = waitForOption(deadlineMs, "bot handle ready")(captured)
            log.info("Bot handle captured; polling assertions")

            waitForCond(deadlineMs, s"chain snapshot contains test CDP $nftHex") {
                handle.chainSnapshot().exists(view =>
                    view.exists { case (_, info) => info.nftName == nftHex }
                )
            }
            waitForCond(deadlineMs, "price cache populated by Pyth Lazer WS") {
                handle.ctx.priceCache
                    .current(Instant.now(), cfg.priceMaxAgeSeconds)
                    .isDefined
            }
            waitForCond(deadlineMs, "evaluator ran ≥ 1 decision pass") {
                handle.evaluationsRun > 0
            }
            log.info(
              "All assertions passed — " +
                  s"evaluationsRun=${handle.evaluationsRun}, " +
                  s"liquidationCandidates=${handle.liquidationCandidates}"
            )
        }
    }

    /** Poll `body` until it returns `Some(value)`, then return that value.
      * Times out at the deadline.
      */
    private def waitForOption[A](deadlineMs: Long, label: String)(body: => Option[A]): A = {
        while System.currentTimeMillis() < deadlineMs do
            body match
                case Some(a) => return a
                case None    => Thread.sleep(500L)
        fail(s"Timed out waiting for: $label")
    }

    /** Poll `body` until it returns `true`. Times out at the deadline. */
    private def waitForCond(deadlineMs: Long, label: String)(body: => Boolean): Unit = {
        while System.currentTimeMillis() < deadlineMs do
            if body then return
            Thread.sleep(500L)
        fail(s"Timed out waiting for: $label")
    }

    /** Reuse if present (idempotent across runs — the warm chain store and
      * the deterministic NFT name make this the fast path on the second
      * run); otherwise open a fresh small healthy CDP via the off-chain
      * tx-builder + Blockfrost submission, and wait for confirmation.
      */
    private def ensureTestCdp(
        appCtx: AppCtx,
        wallet: Wallet,
        nftAsset: AssetName,
        pkh: AddrKeyHash
    ): Unit = {
        val nftHex = nftAsset.bytes.toHex
        appCtx.cdpQueries.findCdpUtxo(nftHex) match
            case Some(_) =>
                log.info(s"Test CDP $nftHex already on chain; reusing")
            case None =>
                log.info(
                  s"Test CDP $nftHex not found; opening " +
                      s"${TestCollateralLovelace / 1_000_000L} ADA / " +
                      s"${TestDebtMicroPusd / 1_000_000L} PUSD"
                )
                val ownerPkh = PubKeyHash(ByteString.unsafeFromArray(pkh.bytes.toArray))
                val builder = appCtx.cdpTransactions.openCdp(
                  collateralLovelace = TestCollateralLovelace,
                  debtPusd           = TestDebtMicroPusd,
                  nftName            = nftAsset,
                  ownerPkh           = ownerPkh,
                  ownerAddr          = wallet.address,
                  now                = Instant.now()
                )
                val completed = builder.complete(appCtx.provider, wallet.address).await(60.seconds)
                val signed    = wallet.sign(completed.transaction)
                appCtx.provider.submitAndPoll(signed).await(180.seconds) match
                    case Right(hash) =>
                        log.info(s"Open CDP confirmed: ${hash.toHex}")
                        // Blockfrost UTxO index lags submission by a few
                        // blocks; give it a beat before the bot starts
                        // querying. 20s comfortably covers preprod's ~20s
                        // block time plus index propagation.
                        Thread.sleep(20_000L)
                    case Left(err) =>
                        fail(s"Failed to open test CDP: $err")
    }

    /** Best-effort cleanup: close the test CDP so the wallet recovers its
      * collateral. Failures are logged but don't fail the test — the
      * primary assertions already passed.
      */
    private def closeTestCdp(appCtx: AppCtx, wallet: Wallet, nftHex: String): Unit = {
        appCtx.cdpQueries.findCdpUtxo(nftHex) match
            case None =>
                log.warn(s"Cleanup requested but test CDP $nftHex not found; skipping")
            case Some(utxo) =>
                val builder = appCtx.cdpTransactions.closeCdp(utxo, wallet.address, Instant.now())
                val completed = builder.complete(appCtx.provider, wallet.address).await(60.seconds)
                val signed    = wallet.sign(completed.transaction)
                appCtx.provider.submitAndPoll(signed).await(180.seconds) match
                    case Right(hash) => log.info(s"Test CDP $nftHex closed: ${hash.toHex}")
                    case Left(err)   => log.warn(s"Cleanup close failed (non-fatal): $err")
    }

    /** Stable per-wallet NFT name: 4-byte ASCII prefix + 28-byte payment-key-hash.
      * Two operators running concurrently can't collide because the PKH is
      * unique, and the same wallet finds the same CDP on every run.
      */
    private def deterministicNft(pkh: AddrKeyHash): AssetName =
        AssetName(ByteString.unsafeFromArray(NftPrefix ++ pkh.bytes.toArray))

    private def paymentKeyHash(addr: Address): AddrKeyHash = addr match
        case s: ShelleyAddress => s.payment match
            case ShelleyPaymentPart.Key(h) => h
            case ShelleyPaymentPart.Script(_) =>
                sys.error("Wallet address must be a payment-key-hash, not a script")
        case _ => sys.error(s"Wallet address must be a Shelley address; got $addr")

    private def renderAddress(a: Address): String = a.encode.getOrElse(a.toHex)

    /** Parse `.env` into a Map (sys.env wins on conflict) so operators don't
      * have to export a dozen vars. Returns `sys.env` unchanged if no `.env`
      * is present. Mirrors the helper in `PreprodLiquidationTest`.
      */
    private def loadEnv(): Map[String, String] = {
        val f = new java.io.File(".env")
        if !f.exists then return sys.env
        val src = Source.fromFile(f)
        try
            val fromFile = src.getLines().filter(_.contains("=")).map { line =>
                val idx = line.indexOf('=')
                line.substring(0, idx).trim -> line.substring(idx + 1).trim
            }.toMap
            fromFile ++ sys.env
        finally src.close()
    }
}
