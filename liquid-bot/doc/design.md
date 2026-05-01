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
  │   (.subscribe / .submit)   │  Blockfrost-backed (fallback / preprod)
  └─────────────┬──────────────┘
                │ RollForward / RollBackward
                ▼
  ┌────────────────────────────┐    ┌──────────────────────┐
  │  ChainFollower             │◀──▶│  ChainPoint store    │
  │  (CDP UTxO view at tip)    │    │  (resume checkpoint) │
  └─────────────┬──────────────┘    └──────────────────────┘
                │ CdpInfo events
                ▼
  ┌────────────────────────────┐    ┌──────────────────────┐
  │  LiquidationDecider (pure) │◀───│  PythClient          │
  │  (LTV ≥ 90% & profitable)  │    │  .fetchPriceUpdate() │
  └─────────────┬──────────────┘    └──────────────────────┘
                │ Liquidate(cdpUtxo, priceUpdate)
                ▼
  ┌────────────────────────────┐
  │  CdpTransactions           │  reused from `core` as-is
  │  .liquidateCdp(...)        │  (CdpTransactions.scala:148)
  └─────────────┬──────────────┘
                ▼
  ┌────────────────────────────┐
  │  Wallet.sign → Provider    │  treats "input already spent" as benign
  │  .submit                   │  (a competing liquidator won the race)
  └────────────────────────────┘
```

## Components

| Component                       | Responsibility                                                                      |
| ------------------------------- | ----------------------------------------------------------------------------------- |
| `BotApp`                        | `OxApp` entry point: decline CLI, builds `BotConfig`, runs the chain follower.      |
| `BotConfig`                     | Env-driven config (network, relay, signing key paths, thresholds, dry-run).         |
| `BotCtx`                        | Wraps `BlockchainStreamProvider`, `AppCtx`, `Wallet`; `AutoCloseable`.              |
| `ChainFollower`                 | Subscribes to script address, maintains CDP UTxO `TrieMap`, handles rollbacks.      |
| `LiquidationDecider`            | Pure: `(CdpInfo, Price, Config) ⇒ Skip \| Liquidate(bps)`.                          |
| `Wallet`                        | Loads signing key (env `PYTHACOIN_BOT_KEY`), signs unsigned txs via `TransactionSigner`. |
| _(inline in `BotApp`)_          | Pyth fetch via `appCtx.pythClient.fetchPriceUpdate()`; submission via `streamProvider.submit`. |

The bot is a thin scheduler — all on-chain semantics (oracle witness, validity window,
mint quantities) live in `commonLib`'s existing `CdpTransactions.liquidateCdp`. Dedicated
`PriceSource` / `Submitter` types and a persisted `ChainPoint` store remain planned
operational hardening rather than separate components today.

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
Rollbacks are handled explicitly: the CDP view is rewound to the rollback target
before any new decision is made, so a CDP that briefly looked liquidatable on a
forked tip can never be acted on after the fork is dropped.

A defensive full rescan runs once on startup and every 60 min — not for correctness,
just to alarm if the streamed view ever drifts from a fresh provider query.

## Failure modes

| Mode                                | Bot behaviour                                                       |
| ----------------------------------- | ------------------------------------------------------------------- |
| Provider/stream disconnect          | Backoff, resume from last `ChainPoint`.                             |
| Pyth Hermes API unreachable         | Skip submission cycle; never use a stale price update.              |
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

- **Unit:** `LiquidationDecider` truth table — LTV around 89/90/91, fee-edge
  profitability, insufficient-PUSD, dryRun.
- **Integration:** extend `integration/` with a Yaci-DevKit scenario — open an
  under-collateralised CDP, drop the simulated ADA/USD price, assert the bot submits
  a `Liquidate` tx and the chain confirms it.
- **Manual preprod:** open a CDP at LTV ≈ 88, push price down via a real Pyth
  update, observe the bot submits.

## Deferred / out of scope

- Batch liquidations (validator-ready, requires multi-CDP planner & coin selection).
- Self-minting PUSD reserves (bot opens its own conservatively-collateralised CDP).
- Priority-fee competition with other liquidators.
- HSM / KMS key custody.
