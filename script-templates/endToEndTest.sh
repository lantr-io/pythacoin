#!/usr/bin/env bash
#
# End-to-end smoke test against preprod/preview.
# Copy this file outside the repo (or into a gitignored path), fill in the
# secrets, then `bash endToEndTest.sh`. See liquid-bot/doc/end-to-end-test.md
# for the full design.

set -euo pipefail

# --- Network -----------------------------------------------------------------
export PYTHACOIN_NETWORK="${PYTHACOIN_NETWORK:-preprod}"

# --- Blockfrost / Pyth credentials ------------------------------------------
export BLOCKFROST_API_KEY="${BLOCKFROST_API_KEY:-fill-me-in}"
export PYTH_POLICY_ID="${PYTH_POLICY_ID:-fill-me-in}"
export PYTH_KEY="${PYTH_KEY:-fill-me-in}"

# --- Test wallet (needs a few ADA for fees; >= debt PUSD if cleanup=true) ---
export PYTHACOIN_BOT_ADDR="${PYTHACOIN_BOT_ADDR:-fill-me-in}"
export PYTHACOIN_BOT_KEY="${PYTHACOIN_BOT_KEY:-fill-me-in}"
export PYTHACOIN_BOT_VKEY="${PYTHACOIN_BOT_VKEY:-fill-me-in}"

# --- Persistent chain store (RocksDB) ---------------------------------------
export PYTHACOIN_CHAIN_STORE_DIR="${PYTHACOIN_CHAIN_STORE_DIR:-./.cache/chain-store-${PYTHACOIN_NETWORK}}"

# --- Mithril bootstrap (recommended for first run; no-op on warm restart) ---
export PYTHACOIN_BOOTSTRAP="${PYTHACOIN_BOOTSTRAP:-mithril}"
export PYTHACOIN_MITHRIL_WORKDIR="${PYTHACOIN_MITHRIL_WORKDIR:-./.cache/mithril-${PYTHACOIN_NETWORK}}"
export PYTHACOIN_MITHRIL_GENESIS_VK="${PYTHACOIN_MITHRIL_GENESIS_VK:-fill-me-in}"
# export PYTHACOIN_MITHRIL_AGGREGATOR_URL=""  # uncomment to override default

# --- Test knobs --------------------------------------------------------------
export PYTHACOIN_E2E_DURATION_SECONDS="${PYTHACOIN_E2E_DURATION_SECONDS:-180}"
export PYTHACOIN_E2E_CLEANUP="${PYTHACOIN_E2E_CLEANUP:-false}"

exec sbtn 'integration/testOnly *PreprodEndToEndTest -- -n pythacoin.integration.PreprodTag'
