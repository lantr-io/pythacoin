import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { TopBar } from "./components/TopBar";
import { OpenCdpForm } from "./components/OpenCdpForm";
import { AllCdpsTable } from "./components/AllCdpsTable";
import { useCdps } from "./hooks/useCdps";
import { useWallet } from "./hooks/useWallet";

export default function App() {
  const wallet = useWallet();
  const { data: cdps } = useCdps();
  const qc = useQueryClient();

  const handleConnect = useCallback(async () => {
    const wallets = window.cardano ? Object.keys(window.cardano) : [];
    const name = wallets.find((w) => w !== "ccvault") ?? wallets[0];
    if (!name) {
      alert("No CIP-30 wallet found. Install Nami, Eternl, or Lace.");
      return;
    }
    await wallet.connect(name);
  }, [wallet]);

  const signAndSubmit = useCallback(
    async (txHex: string) => {
      if (!wallet.walletApi) throw new Error("Wallet not connected");
      console.log("[signAndSubmit] Signing tx, CBOR hex length:", txHex.length);
      const witness = await wallet.walletApi.signTx(txHex, true);
      console.log("[signAndSubmit] Got witness, submitting...");
      const txHash = await wallet.walletApi.submitTx(witness);
      console.log("[signAndSubmit] Submitted! txHash:", txHash);
      return txHash;
    },
    [wallet.walletApi],
  );

  const refresh = useCallback(() => {
    qc.invalidateQueries({ queryKey: ["cdps"] });
  }, [qc]);

  return (
    <div className="min-h-screen flex flex-col">
      <TopBar
        connected={wallet.connected}
        address={wallet.address}
        onConnect={handleConnect}
        onDisconnect={wallet.disconnect}
      />
      <main className="flex-1 max-w-5xl mx-auto w-full px-6 py-8 space-y-8">
        {wallet.connected && wallet.address && (
          <OpenCdpForm
            address={wallet.address}
            onSuccess={refresh}
            signAndSubmit={signAndSubmit}
          />
        )}
        <div>
          <h2 className="font-semibold text-lg mb-4">All CDPs</h2>
          <AllCdpsTable cdps={cdps ?? []} ownerAddress={wallet.address} />
        </div>
      </main>
      <footer className="text-center text-xs text-gray-600 py-4 border-t border-pyth-border">
        Pythacoin - CDP Stablecoin powered by Pyth Network
      </footer>
    </div>
  );
}
