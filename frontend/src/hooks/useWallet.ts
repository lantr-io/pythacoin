import { useCallback, useState } from "react";

interface WalletApi {
  getUsedAddresses(): Promise<string[]>;
  getBalance(): Promise<string>;
  signTx(txHex: string, partial: boolean): Promise<string>;
  submitTx(txHex: string): Promise<string>;
}

export interface WalletState {
  connected: boolean;
  address: string | null;
  balanceLovelace: number | null;
  balancePusd: number | null;
  walletApi: WalletApi | null;
  connect: (walletName: string) => Promise<void>;
  disconnect: () => void;
  refreshBalance: () => void;
}

declare global {
  interface Window {
    cardano?: Record<
      string,
      {
        enable(): Promise<WalletApi>;
        isEnabled(): Promise<boolean>;
      }
    >;
  }
}

// --- Minimal CBOR decoder for CIP-30 getBalance() ---

/** Read a CBOR unsigned integer at `pos`, return [value, nextPos]. */
function readCborUint(hex: string, pos: number): [number, number] {
  const byte0 = parseInt(hex.slice(pos, pos + 2), 16);
  const minor = byte0 & 0x1f;
  if (minor <= 23) return [minor, pos + 2];
  if (minor === 24) return [parseInt(hex.slice(pos + 2, pos + 4), 16), pos + 4];
  if (minor === 25) return [parseInt(hex.slice(pos + 2, pos + 6), 16), pos + 6];
  if (minor === 26) return [parseInt(hex.slice(pos + 2, pos + 10), 16), pos + 10];
  if (minor === 27) return [Number(BigInt("0x" + hex.slice(pos + 2, pos + 18))), pos + 18];
  return [0, pos + 2];
}

/** Read CBOR bytes at `pos`, return [hexString, nextPos]. */
function readCborBytes(hex: string, pos: number): [string, number] {
  const [len, dataStart] = readCborUint(hex, pos);
  const dataEnd = dataStart + len * 2;
  return [hex.slice(dataStart, dataEnd), dataEnd];
}

const PUSD_NAME_HEX = "50555344"; // "PUSD" in hex

interface WalletBalance {
  lovelace: number;
  pusd: number;
}

/** Parse CIP-30 getBalance() CBOR hex into lovelace + PUSD amounts. */
function decodeCborBalance(hex: string, policyId: string | null): WalletBalance {
  const byte0 = parseInt(hex.slice(0, 2), 16);
  const major = byte0 >> 5;

  if (major === 0) {
    // Pure lovelace (no multi-assets)
    const [coin] = readCborUint(hex, 0);
    return { lovelace: coin, pusd: 0 };
  }

  if (major === 4) {
    // Array [coin, multiasset_map]
    const [, coinStart] = readCborUint(hex, 0); // skip array header
    const [coin, mapPos] = readCborUint(hex, coinStart);

    if (!policyId) return { lovelace: coin, pusd: 0 };

    // Parse map: { policy_id_bytes => { asset_name_bytes => quantity } }
    const [mapLen, firstEntryPos] = readCborUint(hex, mapPos);
    let pos = firstEntryPos;
    let pusd = 0;

    for (let i = 0; i < mapLen; i++) {
      const [policyHex, afterPolicy] = readCborBytes(hex, pos);
      const [innerMapLen, innerStart] = readCborUint(hex, afterPolicy);
      pos = innerStart;

      for (let j = 0; j < innerMapLen; j++) {
        const [assetNameHex, afterName] = readCborBytes(hex, pos);
        const [quantity, afterQty] = readCborUint(hex, afterName);
        pos = afterQty;

        if (policyHex === policyId && assetNameHex === PUSD_NAME_HEX) {
          pusd = quantity;
        }
      }
    }

    return { lovelace: coin, pusd };
  }

  return { lovelace: 0, pusd: 0 };
}

export function useWallet(): WalletState {
  const [walletApi, setWalletApi] = useState<WalletApi | null>(null);
  const [address, setAddress] = useState<string | null>(null);
  const [balanceLovelace, setBalanceLovelace] = useState<number | null>(null);
  const [balancePusd, setBalancePusd] = useState<number | null>(null);
  const [policyId, setPolicyId] = useState<string | null>(null);

  const fetchBalance = useCallback(async (api: WalletApi, pid: string | null) => {
    try {
      const cborHex = await api.getBalance();
      const bal = decodeCborBalance(cborHex, pid);
      setBalanceLovelace(bal.lovelace);
      setBalancePusd(bal.pusd);
    } catch (err) {
      console.error("[Wallet] Failed to fetch balance:", err);
    }
  }, []);

  const connect = useCallback(async (walletName: string) => {
    console.log(`[Wallet] Connecting to ${walletName}...`);
    const provider = window.cardano?.[walletName];
    if (!provider) throw new Error(`${walletName} wallet not found`);
    const api = await provider.enable();
    const addrs = await api.getUsedAddresses();
    console.log(`[Wallet] Connected, address:`, addrs[0]);
    setWalletApi(api);
    setAddress(addrs[0] ?? null);
    // Fetch policy ID from backend, then fetch balance
    try {
      const resp = await fetch("/api/price");
      const data = await resp.json();
      const pid = data.policyId ?? null;
      setPolicyId(pid);
      fetchBalance(api, pid);
    } catch {
      fetchBalance(api, null);
    }
  }, [fetchBalance]);

  const disconnect = useCallback(() => {
    setWalletApi(null);
    setAddress(null);
    setBalanceLovelace(null);
    setBalancePusd(null);
  }, []);

  const refreshBalance = useCallback(() => {
    if (walletApi) fetchBalance(walletApi, policyId);
  }, [walletApi, policyId, fetchBalance]);

  return {
    connected: walletApi !== null,
    address,
    balanceLovelace,
    balancePusd,
    walletApi,
    connect,
    disconnect,
    refreshBalance,
  };
}
