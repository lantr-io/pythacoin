import { useCallback, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { TopBar } from "./components/TopBar";
import { OpenCdpForm } from "./components/OpenCdpForm";
import { AllCdpsTable } from "./components/AllCdpsTable";
import { ActionModal } from "./components/ActionModal";
import { useCdps } from "./hooks/useCdps";
import { usePrice } from "./hooks/usePrice";
import { useWallet } from "./hooks/useWallet";
import { api } from "./api/client";
import type { CdpInfo } from "./api/types";

type CdpAction = "borrow" | "repay" | "close" | "liquidate";

function lovelaceToAda(l: number): string {
  return (l / 1_000_000).toFixed(2);
}

function pusdToDisplay(p: number): string {
  return (p / 1_000_000).toFixed(2);
}

export default function App() {
  const wallet = useWallet();
  const { data: cdps } = useCdps();
  const { data: price } = usePrice();
  const qc = useQueryClient();

  const [activeCdp, setActiveCdp] = useState<CdpInfo | null>(null);
  const [activeAction, setActiveAction] = useState<CdpAction | null>(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

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

  const handleAction = useCallback(
    (cdp: CdpInfo, action: CdpAction) => {
      if (!wallet.address) {
        alert("Connect your wallet first.");
        return;
      }
      setActiveCdp(cdp);
      setActiveAction(action);
      setActionError(null);
    },
    [wallet.address],
  );

  const handleConfirm = useCallback(
    async (amount?: number) => {
      if (!activeCdp || !activeAction || !wallet.address) return;
      setActionLoading(true);
      setActionError(null);
      try {
        let resp;
        switch (activeAction) {
          case "close":
            resp = await api.close({
              nftName: activeCdp.nftName,
              ownerAddress: wallet.address,
            });
            break;
          case "borrow":
            if (!amount || amount <= 0) throw new Error("Enter a valid amount");
            resp = await api.borrow({
              nftName: activeCdp.nftName,
              amount: amount,
              ownerAddress: wallet.address,
            });
            break;
          case "repay":
            if (!amount || amount <= 0) throw new Error("Enter a valid amount");
            resp = await api.repay({
              nftName: activeCdp.nftName,
              amount: amount,
              ownerAddress: wallet.address,
            });
            break;
          case "liquidate":
            resp = await api.liquidate({
              nftName: activeCdp.nftName,
              liquidatorAddress: wallet.address,
            });
            break;
        }
        const txHash = await signAndSubmit(resp.txCborHex);
        console.log(`[${activeAction}] Submitted! txHash:`, txHash);
        setActiveCdp(null);
        setActiveAction(null);
        refresh();
      } catch (err) {
        console.error(`[${activeAction}] Error:`, err);
        setActionError(err instanceof Error ? err.message : "Failed");
      } finally {
        setActionLoading(false);
      }
    },
    [activeCdp, activeAction, wallet.address, signAndSubmit, refresh],
  );

  const handleCancel = useCallback(() => {
    setActiveCdp(null);
    setActiveAction(null);
    setActionError(null);
  }, []);

  const modalProps = activeCdp && activeAction ? getModalProps(activeAction, activeCdp) : null;

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
          <AllCdpsTable
            cdps={cdps ?? []}
            adaUsd={price?.adaUsd ?? null}
            ownerAddress={wallet.address}
            onAction={handleAction}
          />
        </div>
      </main>
      <footer className="text-center text-xs text-gray-600 py-4 border-t border-pyth-border">
        Pythacoin - CDP Stablecoin powered by Pyth Network
      </footer>

      {modalProps && (
        <ActionModal
          {...modalProps}
          loading={actionLoading}
          error={actionError}
          onConfirm={handleConfirm}
          onCancel={handleCancel}
        />
      )}
    </div>
  );
}

function getModalProps(action: CdpAction, cdp: CdpInfo) {
  const ada = lovelaceToAda(cdp.collateralLovelace);
  const pusd = pusdToDisplay(cdp.debtPusd);
  switch (action) {
    case "close":
      return {
        title: "Close CDP",
        description: `Burn ${pusd} PUSD debt and return ${ada} ADA collateral.`,
        confirmLabel: "Close CDP",
        confirmColor: "red" as const,
      };
    case "borrow":
      return {
        title: "Borrow PUSD",
        description: `Current debt: ${pusd} PUSD. Collateral: ${ada} ADA.`,
        amountLabel: "Additional PUSD to borrow",
        confirmLabel: "Borrow",
        confirmColor: "purple" as const,
      };
    case "repay":
      return {
        title: "Repay PUSD",
        description: `Current debt: ${pusd} PUSD. Collateral: ${ada} ADA.`,
        amountLabel: "PUSD to repay",
        confirmLabel: "Repay",
        confirmColor: "green" as const,
      };
    case "liquidate":
      return {
        title: "Liquidate CDP",
        description: `Liquidate this under-collateralized CDP (${ada} ADA / ${pusd} PUSD).`,
        confirmLabel: "Liquidate",
        confirmColor: "red" as const,
      };
  }
}
