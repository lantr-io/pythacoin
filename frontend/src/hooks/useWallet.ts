import { useCallback, useState } from "react";

interface WalletApi {
  getUsedAddresses(): Promise<string[]>;
  signTx(txHex: string, partial: boolean): Promise<string>;
  submitTx(txHex: string): Promise<string>;
}

interface WalletState {
  connected: boolean;
  address: string | null;
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

export function useWallet(): WalletState {
  const [walletApi, setWalletApi] = useState<WalletApi | null>(null);
  const [address, setAddress] = useState<string | null>(null);

  const connect = useCallback(async (walletName: string) => {
    console.log(`[Wallet] Connecting to ${walletName}...`);
    const provider = window.cardano?.[walletName];
    if (!provider) throw new Error(`${walletName} wallet not found`);
    const api = await provider.enable();
    const addrs = await api.getUsedAddresses();
    console.log(`[Wallet] Connected, address:`, addrs[0]);
    setWalletApi(api);
    setAddress(addrs[0] ?? null);
  }, []);

  const disconnect = useCallback(() => {
    setWalletApi(null);
    setAddress(null);
  }, []);

  return {
    connected: walletApi !== null,
    address,
    walletApi,
    connect,
    disconnect,
  };
}
