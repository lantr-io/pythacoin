import { useState } from "react";
import { api } from "../api/client";
import { LtvBadge } from "./LtvBadge";

interface Props {
  address: string;
  adaUsd: number | null;
  onSuccess: () => void;
  signAndSubmit: (txHex: string) => Promise<string>;
}

export function OpenCdpForm({ address, adaUsd, onSuccess, signAndSubmit }: Props) {
  const [collateral, setCollateral] = useState("");
  const [borrow, setBorrow] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const borrowNum = parseFloat(borrow) || 0;
  const collateralNum = parseFloat(collateral) || 0;
  const collateralUsd = adaUsd && adaUsd > 0 ? collateralNum * adaUsd : 0;
  const ltv = collateralUsd > 0 ? (borrowNum / collateralUsd) * 100 : 0;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const req = {
        collateralAda: parseFloat(collateral),
        borrowPusd: parseFloat(borrow),
        ownerAddress: address,
      };
      console.log("[OpenCDP] Building tx with:", req);
      const resp = await api.openCdp(req);
      console.log("[OpenCDP] Got unsigned tx, CBOR length:", resp.txCborHex.length);
      console.log("[OpenCDP] Requesting wallet signature...");
      const txHash = await signAndSubmit(resp.txCborHex);
      console.log("[OpenCDP] Submitted! txHash:", txHash);
      setCollateral("");
      setBorrow("");
      onSuccess();
    } catch (err) {
      console.error("[OpenCDP] Error:", err);
      setError(err instanceof Error ? err.message : "Failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <form
      onSubmit={handleSubmit}
      className="bg-pyth-card border border-pyth-border rounded-xl p-5"
    >
      <div className="flex items-center justify-between mb-4">
        <h2 className="font-semibold">Open CDP</h2>
        {(borrowNum > 0 || collateralNum > 0) && <LtvBadge ltv={ltv} />}
      </div>
      <div className="space-y-3">
        <div>
          <label className="text-sm text-gray-400 block mb-1">
            Borrow (PUSD)
          </label>
          <input
            type="number"
            step="0.01"
            value={borrow}
            onChange={(e) => setBorrow(e.target.value)}
            className="w-full bg-pyth-dark border border-pyth-border rounded px-3 py-2 text-sm"
            placeholder="500"
            required
          />
        </div>
        <div>
          <label className="text-sm text-gray-400 block mb-1">
            Collateral (ADA)
          </label>
          <input
            type="number"
            step="0.01"
            value={collateral}
            onChange={(e) => setCollateral(e.target.value)}
            className="w-full bg-pyth-dark border border-pyth-border rounded px-3 py-2 text-sm"
            placeholder="1000"
            required
          />
        </div>
        {error && <p className="text-red-400 text-sm">{error}</p>}
        <button
          type="submit"
          disabled={loading}
          className="w-full bg-pyth-purple py-2 rounded font-semibold text-sm hover:opacity-90 transition disabled:opacity-50"
        >
          {loading ? "Building tx..." : "Open CDP"}
        </button>
      </div>
    </form>
  );
}
