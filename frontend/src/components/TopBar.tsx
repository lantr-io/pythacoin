import { usePrice } from "../hooks/usePrice";
import { WalletButton } from "./WalletButton";

interface Props {
  connected: boolean;
  address: string | null;
  onConnect: () => void;
  onDisconnect: () => void;
}

export function TopBar({ connected, address, onConnect, onDisconnect }: Props) {
  const { data: price } = usePrice();

  return (
    <header className="flex items-center justify-between px-6 py-4 border-b border-pyth-border">
      <div className="flex items-baseline gap-4">
        <h1 className="text-xl font-bold">Pythacoin</h1>
        {price && (
          <span className="text-sm text-gray-400">
            ADA/USD ${price.adaUsd.toFixed(4)}
          </span>
        )}
      </div>
      <WalletButton
        connected={connected}
        address={address}
        onConnect={onConnect}
        onDisconnect={onDisconnect}
      />
    </header>
  );
}
