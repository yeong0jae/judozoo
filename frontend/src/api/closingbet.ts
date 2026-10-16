import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { BetStock, MarketFlow, MarketName } from "../components/closingbet/model";
import type { Moment } from "../components/closingbet/moment";

export type ClosingBetMyBet = {
  stockCode: string;
  stockName: string;
  amountMan: number;
  status: "open" | "filled" | "settled" | "void";
  buyPrice: number | null;
  shares: number | null;
};

export type ClosingBetNow = {
  moment: Moment;
  bettingDay: string | null;
  reviewDay: string | null;
  nextAt: string | null;
  potMan: number;
  players: number;
  me: { nickname: string | null; nextRenameAt: string | null; bet: ClosingBetMyBet | null } | null;
};

export type ClosingBetChecks = { nearHigh: boolean; foreign: boolean; institution: boolean; marketLate: boolean; recentHigh: boolean; afterHold: boolean };

type Details = {
  high: number | null;
  low: number | null;
  foreign: number | null;
  institution: number | null;
  foreignWeek: number | null;
  institutionWeek: number | null;
  recentHighGap: number | null;
  regularClose: number | null;
  checks: ClosingBetChecks | null;
};

export type ClosingBetStock = Details & {
  code: string;
  name: string;
  lead: boolean;
  price: number;
  changeRate: number;
  market: "KOSPI" | "KOSDAQ" | null;
  nxt: boolean | null;
  grade: "S" | "A" | "B" | "C" | null;
  crowd: number;
  potMan: number;
};

export type ClosingBetRoundStock = Details & {
  code: string;
  name: string;
  lead: boolean;
  nxt: boolean;
  closePrice: number;
  changeRate: number;
  market: "KOSPI" | "KOSDAQ";
  grade: "S" | "A" | "B" | "C";
  crowd: number;
  potMan: number;
  sellPrice: number | null;
  rate: number | null;
};

export type ClosingBetRankRow = { rank: number; nickname: string; stockName: string; pnl: number; rate: number };

export type ClosingBetMyResult = {
  stockCode: string;
  stockName: string;
  amountMan: number;
  shares: number | null;
  buyPrice: number | null;
  sellPrice: number | null;
  pnl: number | null;
  rate: number | null;
  profitRank: number | null;
  rateRank: number | null;
};

export type ClosingBetMarketRaw = {
  KOSPI: MarketFlow;
  KOSDAQ: MarketFlow;
  night: { price: number; rate: number } | null;
};

export type ClosingBetRound = {
  day: string;
  settled: boolean;
  players: number;
  potMan: number;
  stocks: ClosingBetRoundStock[];
  byProfit: ClosingBetRankRow[];
  byRate: ClosingBetRankRow[];
  market: ClosingBetMarketRaw | null;
  me: ClosingBetMyResult | null;
};

export type ClosingBetFeedEntry = { id: number; nickname: string; stockName: string; amountMan: number; at: string };

const QK = {
  now: ["closingbet", "now"] as const,
  stocks: ["closingbet", "stocks"] as const,
  market: ["closingbet", "market"] as const,
  feed: ["closingbet", "feed"] as const,
  round: (day: string) => ["closingbet", "round", day] as const,
};

const pace = (moment: Moment | undefined) => (moment === "bet" ? 5_000 : 60_000);

export function useClosingBetNow() {
  return useQuery({
    queryKey: QK.now,
    queryFn: () => apiFetch<ClosingBetNow>("/api/closingbet/now"),
    refetchInterval: (q) => pace(q.state.data?.moment),
  });
}

export function useClosingBetStocks(moment: Moment | undefined) {
  return useQuery({
    queryKey: QK.stocks,
    queryFn: () => apiFetch<ClosingBetStock[]>("/api/closingbet/stocks"),
    enabled: moment === "bet" || moment === "night",
    refetchInterval: moment === "bet" ? 30_000 : false,
  });
}

export function useClosingBetMarket(moment: Moment | undefined, member: boolean) {
  return useQuery({
    queryKey: QK.market,
    queryFn: () => apiFetch<ClosingBetMarketRaw | null>("/api/closingbet/market"),
    enabled: member && (moment === "bet" || moment === "night"),
    refetchInterval: moment === "bet" ? 60_000 : false,
  });
}

export function useClosingBetFeed(moment: Moment | undefined) {
  return useQuery({
    queryKey: QK.feed,
    queryFn: () => apiFetch<ClosingBetFeedEntry[]>("/api/closingbet/feed"),
    enabled: moment === "bet" || moment === "night",
    refetchInterval: moment === "bet" ? 3_000 : false,
  });
}

export function useClosingBetRound(day: string | null | undefined, moment: Moment | undefined) {
  return useQuery({
    queryKey: QK.round(day ?? ""),
    queryFn: () => apiFetch<ClosingBetRound>(`/api/closingbet/rounds/${day}`),
    enabled: !!day,
    retry: false,
    refetchInterval: moment === "result" ? 20_000 : false,
  });
}

function useRefreshAfter() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: QK.now });
    void qc.invalidateQueries({ queryKey: QK.feed });
    void qc.invalidateQueries({ queryKey: QK.stocks });
  };
}

export function usePlaceBet() {
  const refresh = useRefreshAfter();
  return useMutation({
    mutationFn: (body: { code: string; amountMan: number }) =>
      apiFetch<ClosingBetMyBet>("/api/closingbet/bet", { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: refresh,
  });
}

export function useCancelBet() {
  const refresh = useRefreshAfter();
  return useMutation({
    mutationFn: () => apiFetch<null>("/api/closingbet/bet", { method: "DELETE" }),
    onSuccess: refresh,
  });
}

export function useRenameNickname(reviewDay: string | null | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (nickname: string) =>
      apiFetch<{ nickname: string; nextRenameAt: string | null }>("/api/closingbet/me/nickname", { method: "PUT", body: JSON.stringify({ nickname }) }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: QK.now });
      if (reviewDay) void qc.invalidateQueries({ queryKey: QK.round(reviewDay) });
    },
  });
}

const MARKET_NAME: Record<"KOSPI" | "KOSDAQ", MarketName> = { KOSPI: "코스피", KOSDAQ: "코스닥" };

export function toFlows(raw: ClosingBetMarketRaw): Record<MarketName, MarketFlow> {
  return { 코스피: raw.KOSPI, 코스닥: raw.KOSDAQ };
}

const eokOf = (million: number | null) => (million ?? 0) / 100;

export function toBetStock(
  s: { code: string; name: string; lead: boolean; changeRate: number; crowd: number } & Details,
  price: number,
  market: MarketName,
  result?: number | null,
): BetStock {
  return {
    code: s.code,
    name: s.name,
    market,
    lead: s.lead,
    price,
    chg: s.changeRate,
    high: s.high ?? price,
    low: s.low ?? price,
    frgn: eokOf(s.foreign),
    inst: eokOf(s.institution),
    frgn5: eokOf(s.foreignWeek),
    inst5: eokOf(s.institutionWeek),
    recentHighGap: s.recentHighGap,
    regularClose: s.regularClose,
    crowd: s.crowd,
    result: result ?? undefined,
  };
}

export const marketName = (m: "KOSPI" | "KOSDAQ" | null): MarketName => MARKET_NAME[m ?? "KOSPI"];
