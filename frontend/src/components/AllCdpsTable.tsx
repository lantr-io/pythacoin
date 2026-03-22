import type { CdpInfo } from "../api/types";
import { CdpCard } from "./CdpCard";

interface Props {
  cdps: CdpInfo[];
  ownerAddress: string | null;
}

export function AllCdpsTable({ cdps, ownerAddress }: Props) {
  if (cdps.length === 0) {
    return (
      <div className="text-center text-gray-500 py-12">
        No CDPs found. Open the first one!
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      {cdps.map((cdp) => (
        <CdpCard
          key={cdp.nftName}
          cdp={cdp}
          isOwner={ownerAddress === cdp.owner}
        />
      ))}
    </div>
  );
}
