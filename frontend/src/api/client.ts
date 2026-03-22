import type {
  CdpInfo,
  PriceInfo,
  OpenCdpRequest,
  BorrowRequest,
  RepayRequest,
  CloseRequest,
  LiquidateRequest,
  TxResponse,
} from "./types";

const BASE = "/api";

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

export const api = {
  getPrice: () => get<PriceInfo>("/price"),
  listCdps: () => get<CdpInfo[]>("/cdps"),
  openCdp: (req: OpenCdpRequest) => post<TxResponse>("/cdp/open", req),
  borrow: (req: BorrowRequest) => post<TxResponse>("/cdp/borrow", req),
  repay: (req: RepayRequest) => post<TxResponse>("/cdp/repay", req),
  close: (req: CloseRequest) => post<TxResponse>("/cdp/close", req),
  liquidate: (req: LiquidateRequest) =>
    post<TxResponse>("/cdp/liquidate", req),
};
