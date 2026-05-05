package pythacoin.integration

import org.scalatest.funsuite.AnyFunSuite
import pythacoin.{AppCtx, Assets, CdpContract, PythClient, YaciDevKitTest, given}
import pythacoin.bot.PusdSelection
import scalus.cardano.address.{Address, Network}
import scalus.cardano.ledger.*
import scalus.cardano.txbuilder.txBuilder
import scalus.testing.integration.YaciTestContext
import scalus.uplc.builtin.ByteString
import scalus.utils.await

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** End-to-end hermetic test of the liquidation tx flow.
  *
  * Scope: bootstraps a fake Pyth oracle on Yaci-DevKit (alwaysOk script doubles
  * as both the Pyth minting policy and the withdraw script, with custom State
  * UTxO), opens an under-collateralised CDP, then builds + submits a
  * liquidation tx using cached price bytes from `FakeLazerServer.buildPayload`.
  *
  * What this verifies:
  *   - `CdpTransactions.liquidateCdp(... cachedPriceBytes = Some(...))` works
  *     end-to-end against the chain.
  *   - The on-chain `CdpValidator` accepts the liquidation when the cached
  *     price drives LTV past 90%.
  *   - The bot's payload-encoding (offsets in `parsePriceRaw`) round-trips
  *     correctly: bytes built by FakeLazer and parsed by both the bot and the
  *     validator agree on the price.
  *
  * What this does NOT verify (deferred to a separate test):
  *   - The full bot lifecycle (`BotApp` + `ChainFollower` + `PriceStream`
  *     + N2N stream-sync); covered separately, requires Yaci's N2N port.
  *   - Real Pyth Ed25519 verification (deferred to task #13).
  */
class LiquidationFlowTest extends AnyFunSuite with YaciDevKitTest {

    test("liquidation flow: cached low-price bytes drive a valid Liquidate tx") {
        val yaciCtx = createTestContext().asInstanceOf[YaciTestContext]
        val provider = yaciCtx.provider
        val cardanoInfo = yaciCtx.cardanoInfo
        val alice = yaciCtx.alice

        // Yaci-DevKit's testcontainer maps the Yaci-Store API on a *random*
        // host port. Hardcoding 8080 only works if nothing else holds the
        // port; ask the container for the real URL. Strip the trailing
        // slash because PythClient builds `uri"$base/assets/..."`.
        val yaciBlockfrostUrl = container.getYaciStoreApiUrl().stripSuffix("/")

        info(s"Yaci envName=${yaciCtx.envName}, network=${cardanoInfo.network}, store=$yaciBlockfrostUrl")

        // --- Step 1: bootstrap Pyth State ---
        info("Bootstrapping Pyth State UTxO (alwaysOk policy + alwaysOk withdraw script)")
        val pythStateInput =
            PythStateBootstrap.bootstrap(provider, alice.signer, alice.address, cardanoInfo)
        val pythPolicyIdHex = PythStateBootstrap.pythPolicyId.toHex

        // --- Step 2: build AppCtx pointing at Yaci ---
        val cdpScript = CdpContract(ByteString.unsafeFromArray(PythStateBootstrap.pythPolicyId.bytes))
        val appCtx = new AppCtx(
          cardanoInfo,
          provider,
          blockfrostApiKey = "",
          blockfrostBaseUrl = yaciBlockfrostUrl,
          pythPolicyId = PythStateBootstrap.pythPolicyId,
          pythKey = "",
          cdpScript = cdpScript
        )
        info(s"CDP script address: ${appCtx.scriptAddr}")

        // --- Step 3: open a CDP with safe LTV at price $0.75 ---
        // 200 ADA collateral, 70 PUSD debt: at price 0.75 → value $150, LTV 70/150 ≈ 47%.
        // Drop to price 0.30 → value $60, LTV 70/60 ≈ 117% (well past 90%).
        // NB: `openCdp(debtPusd)` expects micro-PUSD (1 PUSD = 1_000_000).
        val initialPrice = 75_000_000L
        val initialBytes = ByteString.unsafeFromArray(FakeLazerServer.buildPayload(initialPrice))
        val debtMicroPusd = 70_000_000L // 70 PUSD

        val nftName = AssetName(ByteString.fromString("test-cdp"))
        val ownerPkh = scalus.cardano.onchain.plutus.v1.PubKeyHash(
          ByteString.unsafeFromArray(alice.addrKeyHash.bytes)
        )

        info("Opening CDP: 200 ADA collateral, 70 PUSD debt, initial price $0.75")
        val openBuilder = appCtx.cdpTransactions.openCdp(
          collateralLovelace = 200_000_000L,
          debtPusd = debtMicroPusd,
          nftName = nftName,
          ownerPkh = ownerPkh,
          ownerAddr = alice.address,
          now = Instant.now(),
          cachedPriceBytes = Some(initialBytes)
        )
        val openCompleted = openBuilder.complete(provider, alice.address).await(30.seconds)
        val signedOpen = alice.signer.sign(openCompleted.transaction)
        provider.submit(signedOpen).await(30.seconds) match
            case Right(hash) => info(s"Open CDP submitted: $hash")
            case Left(err)   => fail(s"Open CDP failed: $err")
        yaciCtx.waitForBlock()

        // --- Step 4: confirm CDP is on-chain ---
        val cdpUtxo = appCtx.cdpQueries.findCdpUtxo(nftName.bytes.toHex)
            .getOrElse(fail("Opened CDP UTxO not found at script address"))
        info(s"CDP UTxO confirmed: ${cdpUtxo.input}")

        // --- Step 5: pick low-price cached bytes that flip LTV past 90% ---
        val lowPrice = 30_000_000L // $0.30/ADA
        val lowBytes = ByteString.unsafeFromArray(FakeLazerServer.buildPayload(lowPrice))

        // Sanity-check the bytes parse back to the price we set.
        val parsedRaw = appCtx.pythClient.parsePriceRaw(lowBytes)
        assert(parsedRaw == lowPrice, s"parsePriceRaw round-trip: $parsedRaw != $lowPrice")

        // The open tx leaves a single big mixed (ADA + PUSD) change UTxO at
        // alice's address — no pure-ADA UTxO eligible for collateral. Split
        // off a small pure-ADA self-pay first; auto-balancing doesn't pick
        // collateral for the liquidate tx because `.spend(liquidatorPusdUtxos)`
        // constrains the input set.
        info("Splitting off a 5-ADA pure-ADA UTxO for collateral")
        given CardanoInfo = cardanoInfo
        val splitTx = txBuilder.payTo(alice.address, Value.lovelace(5_000_000L))
            .complete(provider, alice.address).await(30.seconds)
        provider.submit(alice.signer.sign(splitTx.transaction)).await(30.seconds) match
            case Right(_)  => yaciCtx.waitForBlock()
            case Left(err) => fail(s"Collateral split tx failed: $err")

        // --- Step 6: gather alice's PUSD + collateral UTxOs (post-split) ---
        val allAlice = provider.findUtxos(alice.address).await(15.seconds) match
            case Right(found) => found
            case Left(err) => fail(s"Wallet UTxO query failed: $err")
        val pusdUtxos: Utxos = allAlice.filter {
            case (_, o) => o.value.asset(appCtx.policyId, Assets.Pusd) > 0
        }
        val totalPusd = pusdUtxos.values.map(_.value.asset(appCtx.policyId, Assets.Pusd)).sum
        info(s"Liquidator PUSD UTxOs: ${pusdUtxos.size}, total=$totalPusd μPUSD")
        assert(totalPusd >= debtMicroPusd,
          s"Liquidator must hold ≥$debtMicroPusd μPUSD; has $totalPusd")

        val selectedPusd = PusdSelection.greedy(pusdUtxos, appCtx.policyId, debtMicroPusd)

        val collateralUtxo: Utxo = allAlice
            .find { case (_, o) => o.value.assets.assets.isEmpty && o.value.coin.value >= 5_000_000L && o.value.coin.value < 10_000_000L }
            .map { case (in, out) => Utxo(in, out) }
            .getOrElse(fail(s"No pure-ADA collateral UTxO at alice. Have: $allAlice"))

        // --- Step 7: build + submit the liquidation tx ---
        info(s"Building liquidation tx with cached low-price bytes (raw=$lowPrice ⇒ ~0.30 ADA/USD)")
        val liqBuilder = appCtx.cdpTransactions.liquidateCdp(
          cdpUtxo = cdpUtxo,
          liquidatorAddr = alice.address,
          liquidatorPusdUtxos = selectedPusd,
          now = Instant.now(),
          cachedPriceBytes = Some(lowBytes)
        ).collaterals(collateralUtxo)
        val liqCompleted = liqBuilder.complete(provider, alice.address).await(60.seconds)
        val signedLiq = alice.signer.sign(liqCompleted.transaction)
        provider.submit(signedLiq).await(60.seconds) match
            case Right(hash) => info(s"Liquidation submitted: $hash")
            case Left(err)   => fail(s"Liquidation submission failed: $err")
        yaciCtx.waitForBlock()

        // --- Step 8: assert CDP is gone ---
        val after = appCtx.cdpQueries.findCdpUtxo(nftName.bytes.toHex)
        assert(after.isEmpty, s"CDP UTxO should be gone after liquidation: $after")
        info("CDP UTxO removed — liquidation flow verified end-to-end")
    }
}
