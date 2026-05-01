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
| **REST**      | `POST https://pyth-lazer.dourolabs.app/v1/latest_price`   | ✅ via `PythClient.fetchPriceUpdate()` |
| **WebSocket** | `wss://pyth-lazer-0.dourolabs.app/v1/stream` (+ mirrors)  | ❌ not wired |

Both deliver the same Solana-format signed payload that
`PythClient.parsePriceRaw` decodes. Auth is a Bearer token (`PYTH_KEY`).

### Current cadence: chain-event-driven only

`evaluate(...)` is the only call site of `pythClient.fetchPriceUpdate()`.
`evaluate` runs only on `CdpEvent.Added` and `CdpEvent.Reseeded`. Between
chain events the bot is **completely blind to price moves**.

### Known gap: price-driven re-evaluation

A CDP at LTV ≈ 89% with no on-chain churn but a falling price stays
unliquidated until something else triggers a chain event. The protocol bears
the safety tax of this gap until a separate price-driven trigger lands.

### Planned mitigation (not yet implemented)

Two options, in order of preference:

1. **Periodic ticker** — a forked virtual thread that wakes every N seconds
   (default 30 s, env-configurable), fetches a fresh price via the existing
   REST path, and runs `evaluate(snapshot)` against the full CDP set. Simple,
   robust, predictable load on Pyth's REST API.
2. **WebSocket subscription** — replaces polling with a persistent push
   connection to `pyth-lazer-0.dourolabs.app/v1/stream`. Sub-second latency,
   one connection instead of N HTTPS round-trips, but adds reconnection /
   stale-detection logic. Suitable as a v2 latency upgrade over option 1.

Either lives in its own ox-`fork` inside `BotApp.runWithConfig`'s supervised
scope, sharing `ChainFollower`'s `cdpView` snapshot via a small public
`snapshot()` accessor. A `PriceSource` component listed in the Components
section becomes real once one of these lands.

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

## Failure modes

| Mode                                | Bot behaviour                                                       |
| ----------------------------------- | ------------------------------------------------------------------- |
| Provider/stream disconnect          | Backoff, resume from last `ChainPoint`.                             |
| Pyth Lazer API unreachable          | Skip evaluation pass; never use a stale price update.               |
| Bot wallet out of PUSD or ADA       | Log & idle; no submission.                                          |
| Competing liquidator wins the race  | Submission rejection classified as benign; drop & wait for next event.|
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

CLI: `pythacoin-bot {dry-run|start}`. The `dry-run` subcommand is OR'd with the env
flag — either is enough to suppress submission.

## Testing

- **Unit (`liquidBot/test`, 13 tests):**
  - `LiquidationDecider` (6) — LTV around 89/90/91, fee-edge profitability,
    insufficient-PUSD, zero-collateral / zero-debt / zero-price guards.
  - `PusdSelection` (7) — single-pick, multi-pick, sort independence, exact
    match, insufficient-total fallback, tie-determinism.
- **Integration (planned):** extend `integration/` with a Yaci-DevKit scenario
  — open an under-collateralised CDP, drop the simulated ADA/USD price, assert
  the bot submits a `Liquidate` tx and the chain confirms it.
- **Manual preprod (planned):** open a CDP at LTV ≈ 88, push price down via a
  real Pyth update, observe the bot submits.

## Deferred / out of scope

- **Price-driven trigger** (periodic ticker or Pyth WS subscription — see
  *Price source* above). Closes the bot's "blind between chain events" gap.
- Batch liquidations (validator-ready, requires multi-CDP planner & coin selection).
- Self-minting PUSD reserves (bot opens its own conservatively-collateralised CDP).
- Priority-fee competition with other liquidators.
- HSM / KMS key custody.
