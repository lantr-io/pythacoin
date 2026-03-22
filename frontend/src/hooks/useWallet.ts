import { useCallback, useState } from "react";

interface WalletApi {
  getUsedAddresses(): Promise<string[]>;
  getBalance(): Promise<string>;
  signTx(txHex: string, partial: boolean): Promise<string>;
  submitTx(txHex: string): Promise<string>;
}

interface WalletState {
  connected: boolean;
  address: string | null;
  balanceLovelace: number | null;
  walletApi: WalletApi | null;
  connect: (walletName: string) => Promise<void>;
  disconnect: () => void;
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

/** Decode a CBOR-encoded lovelace balance (hex string) from CIP-30 getBalance(). */
function decodeCborLovelace(hex: string): number {
  const byte0 = parseInt(hex.slice(0, 2), 16);
  const major = byte0 >> 5;
  const minor = byte0 & 0x1f;

  if (major === 0) {
    // Unsigned integer
    if (minor <= 23) return minor;
    if (minor === 24) return parseInt(hex.slice(2, 4), 16);
    if (minor === 25) return parseInt(hex.slice(2, 6), 16);
    if (minor === 26) return parseInt(hex.slice(2, 10), 16);
    if (minor === 27) return Number(BigInt("0x" + hex.slice(2, 18)));
  }
  if (major === 4) {
    // Array [lovelace, multiasset] — extract first element
    return decodeCborLovelace(hex.slice(2));
  }
  return 0;
}

export function useWallet(): WalletState {
  const [walletApi, setWalletApi] = useState<WalletApi | null>(null);
  const [address, setAddress] = useState<string | null>(null);
  const [balanceLovelace, setBalanceLovelace] = useState<number | null>(null);

  const fetchBalance = useCallback(async (api: WalletApi) => {
    try {
      const cborHex = await api.getBalance();
      setBalanceLovelace(decodeCborLovelace(cborHex));
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
    fetchBalance(api);
  }, [fetchBalance]);

  const disconnect = useCallback(() => {
    setWalletApi(null);
    setAddress(null);
    setBalanceLovelace(null);
  }, []);

  return {
    connected: walletApi !== null,
    address,
    balanceLovelace,
    walletApi,
    connect,
    disconnect,
  };
}
