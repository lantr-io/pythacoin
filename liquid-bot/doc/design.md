# Liquid Bot — Design

## Goal

Autonomous keeper that liquidates Pythacoin CDPs whose LTV ≥ `LIQ_THRESHOLD` (90%,
defined in `CdpValidator.scala:47`). Runs on top of the embedded scalus-node, follows
the chain locally, watches the CDP script address, and submits liquidation transactions
without operator intervention.

Out of scope (v1): batched liquidations, profit-maximising priority bidding,
self-minted PUSD reserves, multi-collateral.

## Architecture

```
  ┌────────────────────────────┐
  │  BlockchainStreamProvider  │  embedded scalus-node (primary)
  │   (.subscribe / .submit)   │  appCtx.provider as backup (Blockfrost / Yaci)
  └─────────────┬──────────────┘
                │ UtxoEvent (Created / Spent / RolledBack)
                ▼
  ┌────────────────────────────┐
  │  ChainFollower             │  TrieMap[input → (Utxo, CdpInfo)]
  │  (CDP view, parsed once)   │  parseCdpInfo at insert; readOnlySnapshot on rollback
  └─────────────┬──────────────┘
                │ CdpEvent: Added(utxo, info) | Removed(input) | Reseeded(all)
                ▼
  ┌────────────────────────────┐    ┌──────────────────────┐
  │  BotApp.evaluate           │◀───│  PythClient          │
  │  (decider over scoped set) │    │  .parsePriceRaw(...) │
  └─────────────┬──────────────┘    └──────────────────────┘
                │ Liquidate(cdpUtxo, info)
                ▼
  ┌────────────────────────────┐
  │  PusdSelection.greedy →    │  pick min PUSD-UTxO subset covering debt
  │  CdpTransactions           │  liquidateCdp builder reused from commonLib
  │  .liquidateCdp(...)        │  (CdpTransactions.scala:148)
  └─────────────┬──────────────┘
                ▼
  ┌────────────────────────────┐
  │  Wallet.sign → Provider    │  UtxoNotAvailable classified as benign
  │  .submit                   │  (competing liquidator OR own PUSD UTxO spent)
  └────────────────────────────┘
```

Per-event work is **scoped to what changed**:

| `CdpEvent`                | Work performed                                                  |
| ------------------------- | --------------------------------------------------------------- |
| `Added(utxo, info)`       | 1 Pyth fetch + 1 wallet UTxO query + decider on **1** CDP       |
| `Removed(input)`          | nothing — the CDP is gone                                       |
| `Reseeded(all)` (rollback)| 1 Pyth fetch + 1 wallet UTxO query + decider on **all** CDPs    |

## Components

| Component                       | Responsibility                                                                      |
| ------------------------------- | ----------------------------------------------------------------------------------- |
| `BotApp`                        | `OxApp` entry point: decline CLI, builds `BotConfig`, runs the chain follower.      |
| `BotConfig`                     | Env-driven config (network, relay, signing key paths, thresholds, dry-run).         |
| `BotCtx`                        | Wraps `BlockchainStreamProvider`, `AppCtx`, `Wallet`; `AutoCloseable`.              |
| `ChainFollower`                 | Subscribes to script address, maintains `TrieMap[input → (Utxo, CdpInfo)]`, emits `CdpEvent` deltas, handles rollbacks. |
| `CdpEvent`                      | ADT — `Added` / `Removed` / `Reseeded`; lets the consumer scope work to what changed. |
| `LiquidationDecider`            | Pure: `(CdpInfo, Price, Config) ⇒ Skip \| Liquidate(bps)`.                          |
| `PusdSelection`                 | Greedy-descending coin selection — minimum PUSD-UTxO subset covering debt.          |
| `Wallet`                        | Loads signing key (env `PYTHACOIN_BOT_KEY`), signs unsigned txs via `TransactionSigner`. |
| _(inline in `BotApp`)_          | Pyth fetch via `appCtx.pythClient`; submission via `streamProvider.submit`.         |

The bot is a thin scheduler — all on-chain semantics (oracle witness, validity window,
mint quantities) live in `commonLib`'s existing `CdpTransactions.liquidateCdp`. A
persisted `ChainPoint` store and a dedicated `PriceSource` (see next section) are
planned operational hardening, not standalone components today.

## Price source

Pyth ADA/USD prices live **off-chain** at Pyth Lazer (recently rebranded
"Pyth Pro"). Nothing on Cardano carries the latest price — the on-chain Pyth
State UTxO holds only the withdraw-script hash + governance config, never a
spot value. Prices reach the chain only when a transaction (ours or someone
else's) carries a freshly-signed update as the redeemer of a zero-reward
withdrawal that the Pyth withdraw script verifies.

Two transports are available from Pyth today:

| Transport     | Endpoint                                                  | Used today? |
| ------------- | --------------------------------------------------------- | ----------- |
| **REST**      | `POST https://pyth-lazer.dourolabs.app/v1/latest_price`   | ✅ — `CdpTransactions.fetchPythInfo` REST-fallback (cache miss) and the HTTP server's open/borrow/repay endpoints. |
| **WebSocket** | `wss://pyth-lazer-0.dourolabs.app/v1/stream` (+ mirrors)  | ✅ — `PriceStream` keeps `priceCache` warm; bot uses cached bytes on every `liquidateCdp` submit. |

Both deliver the same Solana-format signed payload that
`PythClient.parsePriceRaw` decodes. Auth is a Bearer token (`PYTH_KEY`).
Only `formats: ["solana"]` is requested — the deployed Pyth Cardano
withdraw script is hardcoded to the Solana envelope and Ed25519
signature, so the other Lazer formats (`evm`, `leEcdsa`, `leUnsigned`)
would fail phase-2 validation.

### Cadence: chain events + WS price pushes

`evaluate(...)` runs on **either** trigger:

- `ChainFollower` callbacks (`CdpEvent.Added` / `Reseeded`) — view changed.
- `PriceStream` push observed via `priceLoop` polling `priceCache.fetchedAt` — price moved.

Both funnel through `tryEvaluate` (skip-if-busy guard) so a slow on-chain
submission can't backlog the 5×/sec push stream. Cache freshness is
wall-clock based: an entry older than `priceMaxAgeSeconds` reads as `None`
and `evaluate` skips the pass instead of acting on a price the validator
might reject.

### Implemented trigger: Pyth Lazer WebSocket subscription

A persistent WebSocket connection to `wss://pyth-lazer-0.dourolabs.app/v1/stream`
pushes signed updates at the channel rate (`fixed_rate@200ms` ⇒ 5×/sec). The bot
caches the latest payload and re-evaluates the CDP view on every push — no
periodic timer, no per-evaluation HTTP fetch.

#### Why WS over REST polling

The bot is a liquidation race participant. Pyth Lazer is built for WS as the
primary low-latency path; REST is the one-shot fallback. A REST poller at 30 s
throws away 99% of the price signal a 200 ms-channel WS exposes, and a
competing keeper polling at 5 s wins every race. Pythacoin's `Liquidate` action
is permissionless precisely because the protocol benefits from fast keepers.

#### Components

```
  ┌─────────────────────────────┐    ┌──────────────────────────┐
  │  PriceStream (ox fork)      │───▶│  PriceCache              │
  │  - WS connect + subscribe   │    │   AtomicReference        │
  │  - on push: parse + cache   │    │     Option[Cached(bytes, │
  │  - on close: backoff 1s→30s │    │       raw, fetchedAt)]   │
  └─────────────────────────────┘    └────────────┬─────────────┘
                                                  │ read by
                                                  ▼
  ┌─────────────────────────────┐    ┌──────────────────────────┐
  │  ChainFollower              │    │  priceLoop (ox fork)     │
  │  onChange(CdpEvent)         │    │  - poll fetchedAt        │
  │   ↓ Added/Reseeded          │    │  - on new push OR        │
  │  tryEvaluate(snapshot)      │    │    viewDirty=true:       │
  │   (sets viewDirty if        │    │    tryEvaluate(snapshot) │
  │    Reseeded was dropped)    │    │  - clear viewDirty on    │
  └────────────┬────────────────┘    │    successful pass       │
               │                     └────────────┬─────────────┘
               │              ┌───────────────────┘
               ▼              ▼
        ┌──────────────────────────────────┐    ┌──────────────────────────┐
        │  tryEvaluate                     │───▶│  CdpTransactions         │
        │  (AtomicBoolean evalBusy:        │    │  .liquidateCdp(...,      │
        │   drop, don't queue)             │    │   cachedPriceBytes)      │
        └──────────────────────────────────┘    └──────────────────────────┘
```

| Concern               | Decision                                                                                       |
| --------------------- | ---------------------------------------------------------------------------------------------- |
| **Source**            | `wss://pyth-lazer-0.dourolabs.app/v1/stream` (with mirrors), `fixed_rate@200ms` channel.       |
| **Auth**              | Same Bearer token as REST (`PYTH_KEY`).                                                        |
| **Cache shape**       | `AtomicReference[Option[CachedPrice]]` — `(updateBytes, priceRaw, fetchedAt: Instant)`.        |
| **Cache freshness**   | Drop entry if `now - fetchedAt > maxAge` (default 60 s, well under the validator's ±600 s window). `evaluate` reads `None` → skip pass. |
| **Trigger cadence**   | Self-adjusts to channel rate; no env knob. Skip-if-busy means evaluations don't pile up.       |
| **Tx-build coupling** | `CdpTransactions.fetchPythInfo` learns to accept `Option[ByteString]`; if `Some`, reuse cached bytes; if `None`, fall back to the existing one-shot REST fetch. Bot supplies cached bytes on every submit. |
| **Reconnect**         | On WS close: exponential backoff (1 s, 2 s, 4 s, … capped at 30 s); cache continues to age out independently, so during the gap the bot stops evaluating cleanly. |
| **Stream parsing**    | Pyth Lazer's WS messages wrap the same Solana payload bytes that REST returns; `PythClient.parsePriceRaw` is reused unchanged. The WS-specific layer is just envelope/handshake handling. |
| **Mutual exclusion**  | `AtomicBoolean evalBusy`: chain-event handler and WS-push handler both go through `tryEvaluate`, which skips (not queues) if the previous pass is still running. |
| **Dropped-Reseeded recovery** | `Reseeded` arriving while `evalBusy=true` would otherwise lose its trigger. `AtomicBoolean viewDirty` is set in the `Reseeded` handler and cleared only on a successful `tryEvaluate`; `priceLoop` re-fires `tryEvaluate(snapshot)` while `viewDirty=true` even without a new WS push. Worst-case post-rollback latency: one priceLoop tick (~50 ms) instead of "next WS push". |
| **Dry-run**           | WS still runs in dry-run; `evaluate` respects `cfg.dryRun` and skips submission only.          |
| **Shutdown**          | WS fork lives in `OxApp`'s supervised scope — SIGTERM cancels both the chain follower and the WS subscription cleanly.|

#### New env vars

| Variable                              | Required | Default                                            | Notes                                                              |
| ------------------------------------- | -------- | -------------------------------------------------- | ------------------------------------------------------------------ |
| `PYTHACOIN_PYTH_WS_URL`               | no       | `wss://pyth-lazer-0.dourolabs.app/v1/stream`       | Override for mirrors / failover                                    |
| `PYTHACOIN_PYTH_CHANNEL`              | no       | `fixed_rate@200ms`                                 | `fixed_rate@200ms` \| `fixed_rate@50ms` \| `real_time` per Pyth.   |
| `PYTHACOIN_PRICE_MAX_AGE_SECONDS`     | no       | `60`                                               | Drop cached price after this; `evaluate` skips while cache empty.  |

#### Component additions (as shipped)

| Component        | Role                                                                                                                                      |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `PriceCache`     | `AtomicReference[Option[Cached]]`. Read-side wall-clock staleness check (`now - fetchedAt <= maxAgeSeconds`); no background reaper.       |
| `PriceStream`    | `java.net.http.WebSocket` (no extra deps). Connect → subscribe → on each `streamUpdated` push: extract `solana.data`, base64-decode, `parsePriceRaw`, `cache.set`. Backoff loop on close/error. |
| `BotCtx.priceCache` | Single instance owned by `BotCtx`; `PriceStream` writes, `BotApp.evaluate` and `priceLoop` read.                                       |
| `ChainFollower.snapshot()` | O(1) `TrieMap.readOnlySnapshot().values` exposed for `priceLoop` so the WS-driven pass evaluates the same view the follower is tracking — without re-querying the script address per push. |
| `BotApp.priceLoop` | 50 ms polling fork that re-fires `tryEvaluate(follower.snapshot())` whenever `priceCache.fetchedAt` advances OR `viewDirty` is set.     |
| `BotApp.tryEvaluate` | Skip-if-busy gate via `AtomicBoolean evalBusy`; returns `Boolean` so callers can leave a "retry next tick" flag set on a dropped pass. |

#### Failure modes (additions to the table further down)

| Mode                                | Bot behaviour                                                       |
| ----------------------------------- | ------------------------------------------------------------------- |
| Pyth Lazer WS disconnect            | Backoff-reconnect (1 s → 30 s); cache ages out → `evaluate` skips passes. |
| Pyth Lazer WS message decode error  | Log warn, drop the message, keep the connection.                    |
| Cache stale (no push for `maxAge`)  | `evaluate` returns early with a warn; submission gets the same gate.|
| Reseeded dropped while evalBusy     | `viewDirty` set; `priceLoop` retries on its next tick (~50 ms).     |

#### Open question — settled

Whether to refetch a fresh price via REST at submit time was deferred as
"open question". **Settled: trust the cache.** The stale-cache gate
(`priceMaxAgeSeconds`) already prevents the bot from acting on a price
the validator would reject. Adding a per-submit REST round-trip would
defeat the cache's purpose and add ~200 ms of latency to the liquidation
race. No `PYTHACOIN_PRICE_REFETCH_ON_SUBMIT` knob is exposed.

## Liquidation policy (v1)

- **Trigger:** `LTV ≥ 90%` computed against the same Pyth price-update bytes that will
  be embedded in the redeemer (no off-chain/on-chain price drift).
- **Profitability filter:** liquidate only if
  `collateral_lovelace − tx_fee − minUtxo > 0`. Bot earns the seized ADA collateral
  net of fees; debt is burned from its own PUSD balance.
- **One CDP per tx.** Validator already supports batching (`CdpValidator.scala:419`),
  but batching is deferred to v2.
- **PUSD reserves:** operator-managed in v1. If the bot's PUSD balance is below the
  largest eligible debt, it logs `InsufficientPusd` and idles.

## Event delivery & rollbacks

Subscription is correct because `BlockchainStreamProvider` streams ordered
`RollForward` / `RollBackward` events resumed from a persisted `ChainPoint`. On
restart the bot resumes from its checkpoint — events aren't silently dropped.
Rollbacks are handled explicitly: `ChainFollower.reseed()` re-derives the entire
CDP view from `findUtxos(scriptAddr)` so a CDP that briefly looked liquidatable
on a forked tip can never be acted on after the fork is dropped.

If the reseed query itself fails (transient connectivity), the previous view
is preserved AND the bot **does not** emit `Reseeded` for that pass — it would
otherwise evaluate against a known-inconsistent view. A successful reseed on a
later event recovers.

### Price replay on rollback: not needed

The price cache is **chain-state-independent** — Pyth-signed bytes carry an
off-chain timestamp (payload header) and the cache freshness rule is wall-clock
based (`now - fetchedAt > maxAge`). Rollbacks can't poison or invalidate it.

The bot's decision model is "current chain state + current price"; it never
needs to reconstruct historical prices. Concretely:

- **Orphaned liquidation**: if our submitted tx is rolled back, the CDP it
  spent reappears in the reseeded `cdpView`. `Reseeded` fires → `evaluate`
  runs → if still liquidatable at the cached price, the bot resubmits with a
  fresh validity window. If the price has since recovered, the decider returns
  `Skip` — correct, because submitting against the old price would fail the
  on-chain LTV check anyway.
- **In-flight tx, validity window expires during the reorg**: chain rejects
  with `OutsideValidityInterval`. Treated as benign in the same branch as
  `UtxoNotAvailable`; the next event triggers a fresh build with a new window.

## Failure modes

| Mode                                | Bot behaviour                                                       |
| ----------------------------------- | ------------------------------------------------------------------- |
| Provider/stream disconnect          | Backoff, resume from last `ChainPoint`.                             |
| Pyth Lazer API unreachable          | Skip evaluation pass; never use a stale price update.               |
| Bot wallet out of PUSD or ADA       | Log & idle; no submission.                                          |
| Competing liquidator wins the race  | Submission rejection classified as benign; drop & wait for next event.|
| In-flight tx orphaned by rollback   | Validity window may expire → `TransactionExpired` rejection; next event resubmits. |
| Tip lag > N slots                   | Emit warning metric; keep running.                                  |

## Configuration

`BotConfig.fromEnv()` reads the following environment variables (defaults in `[]`):

| Variable                          | Required | Default                                | Notes                                       |
| --------------------------------- | -------- | -------------------------------------- | ------------------------------------------- |
| `BLOCKFROST_API_KEY`              | yes      | —                                      | Used by `AppCtx.provider` (Blockfrost backup) |
| `PYTH_POLICY_ID`                  | yes      | —                                      | Pyth oracle deployment policy hex           |
| `PYTH_KEY`                        | yes      | —                                      | Pyth Lazer bearer token                     |
| `PYTHACOIN_BOT_ADDR`              | yes      | —                                      | Bot wallet bech32                           |
| `PYTHACOIN_BOT_KEY`               | yes      | —                                      | Ed25519 signing key (64 hex chars)          |
| `PYTHACOIN_BOT_VKEY`              | yes      | —                                      | Ed25519 verification key (64 hex chars)     |
| `PYTHACOIN_NETWORK`               | no       | `preprod`                              | `preprod` \| `mainnet`                      |
| `PYTHACOIN_RELAY_HOST`            | no       | `preprod-node.play.dev.cardano.org`    | N2N relay                                   |
| `PYTHACOIN_RELAY_PORT`            | no       | `3001`                                 | N2N port                                    |
| `PYTHACOIN_APP_ID`                | no       | `io.lantr.pythacoin.bot`               | Engine persistence appId                    |
| `PYTHACOIN_MIN_LTV_BPS`           | no       | `9000`                                 | LTV trigger in bps                          |
| `PYTHACOIN_MIN_PROFIT_LOVELACE`   | no       | `2000000`                              | Min collateral floor (≈ tx_fee + minUtxo)   |
| `PYTHACOIN_DRY_RUN`               | no       | `false`                                | Force dry-run (also via `dry-run` subcommand) |
| `PYTHACOIN_PYTH_WS_URL`           | no       | `wss://pyth-lazer-0.dourolabs.app/v1/stream` | Pyth Lazer WS endpoint (override for mirrors) |
| `PYTHACOIN_PYTH_CHANNEL`          | no       | `fixed_rate@200ms`                     | `fixed_rate@200ms` \| `fixed_rate@50ms` \| `real_time` |
| `PYTHACOIN_PRICE_MAX_AGE_SECONDS` | no       | `60`                                   | Drop cached price after this; `evaluate` skips while cache empty |

CLI: `pythacoin-bot {dry-run|start}`. The `dry-run` subcommand is OR'd with the env
flag — either is enough to suppress submission.

## Testing

- **Unit (`liquidBot/test`, 18 tests):**
  - `LiquidationDecider` (6) — LTV around 89/90/91, fee-edge profitability,
    insufficient-PUSD, zero-collateral / zero-debt / zero-price guards.
  - `PusdSelection` (7) — single-pick, multi-pick, sort independence, exact
    match, insufficient-total fallback, tie-determinism.
  - `PriceCache` (5) — empty-before-set, fresh window, boundary-inclusive,
    aged-out, latest-write-wins.
- **Integration (planned):** extend `integration/` with a Yaci-DevKit scenario
  — open an under-collateralised CDP, drop the simulated ADA/USD price, assert
  the bot submits a `Liquidate` tx and the chain confirms it.
- **Manual preprod (planned):** open a CDP at LTV ≈ 88, push price down via a
  real Pyth update, observe the bot submits.

## Deferred / out of scope

- Batch liquidations (validator-ready, requires multi-CDP planner & coin selection).
- Self-minting PUSD reserves (bot opens its own conservatively-collateralised CDP).
- Priority-fee competition with other liquidators.
- HSM / KMS key custody.
