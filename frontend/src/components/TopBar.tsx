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
          <span className="text-base text-gray-200">
            ADA/USD <span className="font-semibold">${price.adaUsd.toFixed(4)}</span>
            <span className="text-xs text-gray-500 ml-1">via Pyth</span>
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
