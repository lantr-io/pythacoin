# End-to-end testing on a real testnet (preprod / preview)

The hermetic Yaci-DevKit suite (`integration/LiquidationFlowTest`) covers
script wiring and tx-build paths against a synthetic chain and a fake
Pyth Lazer. It does **not** exercise:

- the embedded scalus-node N2N stream,
- a real Pyth Lazer WS subscription,
- chain-store warm-restart across runs,
- CDP discovery on a chain that has more than one CDP at our script address.

The end-to-end test (`PreprodEndToEndTest`, planned) closes that gap by
running the full bot stack against preprod or preview, with a real
ChainStore-backed warm restart so subsequent runs cost seconds, not the
full chain sync.

This document describes the design. The hooks the test depends on are
already in the codebase; the test class itself is the next deliverable.

## What the test does

1. **Load env** (`.env` ∪ `sys.env`, sys wins on conflict). Refuse to run
   unless `PYTHACOIN_NETWORK ∈ {preprod, preview}` and the wallet /
   Blockfrost / Pyth credentials are present.
2. **Resolve chain-store path** from `PYTHACOIN_CHAIN_STORE_DIR`
   (recommend `./.cache/chain-store-${network}`).
   - Directory missing → bot creates it, syncs from genesis (slow first
     run; ~minutes once a Mithril snapshot bootstrap is wired).
   - Directory present → bot resumes from the last persisted ChainPoint
     (fast: seconds). This is the "restore-if-absent" semantic the user
     asked for.
3. **Ensure a deterministic test CDP exists.** The test uses an NFT name
   derived from a stable prefix + the wallet's payment-key-hash so two
   developers running concurrently don't collide.
   - Found via `appCtx.cdpQueries.findCdpUtxo(nftName)` → reuse (fast,
     idempotent across runs).
   - Not found → submit a small healthy CDP (e.g. 50 ADA collateral,
     5 PUSD debt) with current cached price; wait for confirmation.
4. **Run the bot in dry-run** for `PYTHACOIN_E2E_DURATION_SECONDS`
   (default 180s) using the `BotApp.runWithConfig(cfg, observe)` overload
   that yields a `BotHandle` to the test thread.
5. **Assert via `BotHandle`** within the run window:
   - `chainSnapshot()` eventually contains our test CDP,
   - `priceCache.current(...)` returns `Some` (real WS pushes arrived),
   - `evaluationsRun > 0` (at least one decider pass executed),
   - the bot did not crash.
6. **Cancel the supervised scope** when assertions pass or the timeout
   elapses.
7. **Cleanup is opt-in.** Default leaves the test CDP on chain so the
   next run is faster; setting `PYTHACOIN_E2E_CLEANUP=true` closes it
   at end-of-test.

The test is tagged `PreprodTag` and so is excluded from the default
`integration/test` pass. Run explicitly with:

```sh
sbt 'integration/testOnly *PreprodEndToEnd* -- -n pythacoin.integration.PreprodTag'
```

## Required environment

| Var | Purpose |
| --- | --- |
| `PYTHACOIN_NETWORK` | `preprod` or `preview`. |
| `BLOCKFROST_API_KEY` | Backup-source provider for the embedded node. |
| `PYTH_POLICY_ID` | Pyth State minting policy on the target network. |
| `PYTH_KEY` | Pyth Lazer auth token. |
| `PYTHACOIN_BOT_ADDR` / `PYTHACOIN_BOT_KEY` / `PYTHACOIN_BOT_VKEY` | Test wallet. Must hold a few ADA for fees and (if cleanup is enabled) ≥ debt PUSD. |
| `PYTHACOIN_CHAIN_STORE_DIR` | Persistent RocksDB path. Auto-created when missing. |
| `PYTHACOIN_E2E_DURATION_SECONDS` | Run window, default 180. |
| `PYTHACOIN_E2E_CLEANUP` | If `true`, close the test CDP on success. |

## Hooks the test relies on (already in main)

- `PYTHACOIN_NETWORK=preview` parsed by `BotConfig.fromMap`;
  `BotNetwork.Preview` carries the right magic and Blockfrost endpoint.
- `PYTHACOIN_CHAIN_STORE_DIR` threaded through `BotConfig.chainStoreDir`
  into `BotCtx`, which constructs a `KvChainStore(RocksDbKvStore.open(path))`
  and passes it on `StreamProviderConfig.chainStore`. The
  `OxBlockchainStreamProvider` lifecycle calls `.close()` for us via
  `persistenceTeardown`.
- `BotHandle` (in `liquid-bot/src/main/scala/pythacoin/bot/BotHandle.scala`)
  exposes `chainSnapshot()`, `evaluationsRun`, `liquidationCandidates`,
  `liquidationsAttempted`, `liquidationsSubmitted`. The
  `BotApp.runWithConfig(cfg, observe)` overload hands one to the caller
  before entering the chain-follower loop.
- `Evaluator` updates atomic counters per pass / candidate / attempt /
  submission so the test asserts on forward progress without scraping
  logs.

## Out of scope (for later)

- **Mithril-bootstrapped chain store.** Ramps the first-run sync from
  full-history (long) to snapshot-restore + tail (short). Requires
  `scalus-chain-store-mithril` integration; an opt-in
  `PYTHACOIN_BOOTSTRAP=mithril` knob would be the natural shape.
- **Forced-liquidation drill.** `dryRun=false` against a CDP whose
  `minLtvBps` we've lowered for the test, so the bot actually submits
  a liquidation against preprod. Useful as a separate, longer-running
  test gated behind a stricter tag (`PreprodLiquidateTag`).
- **Multi-wallet concurrent run.** Today's NFT-name scheme is per-wallet
  unique, so two operators don't collide; once we have many parallel
  testers, a shared coordinator becomes useful.

## Risks / things to watch

- `priceCache.current` is gated on `priceMaxAgeSeconds` (default 60).
  WS reconnection storms or Lazer outages mean a slower run window may
  be needed; the assertion waits with a generous deadline.
- Preview's relay defaults are baked into `BotConfig` but the actual
  endpoint may shift. Use `PYTHACOIN_RELAY_HOST` / `PYTHACOIN_RELAY_PORT`
  if the play.dev.cardano.org endpoint moves.
- ChainStore warm-restart is single-process: RocksDB's `LOCK` enforces
  it. Concurrent runs against the same path will fail loudly — keep one
  path per test runner.
