import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { todayStr } from "../components/common/DateNavigator";
import type {
  BreakoutRadarResponse,
  DailyCandleItem,
  CandidateStocksResponse,
  InvestorTrendDay,
  KospiIndex,
  MarketIndex,
  LeadingStockDetailResponse,
  MinuteCandleItem,
  IndexMinuteCandleItem,
  MarketSignalEventsResponse,
  MarketType,
  MarketStatus,
  OverseasStockRankItem,
  SignalEventsResponse,
  StockSearchResult,
  ThemeCalendarResponse,
} from "../types";

export const QK = {
  instanceInfo: ["system", "instance"] as const,
  marketStatus: ["market", "status"] as const,
  stockSearch: (q: string) => ["stocks", "search", q] as const,
  leadingStockCandidates: (minChangeRate: number) =>
    ["leading-stocks", "candidates", minChangeRate] as const,
  breakoutRadar: (minChangeRate: number) =>
    ["leading-stocks", "breakout-radar", minChangeRate] as const,
  signalEvents: (date: string) =>
    ["leading-stocks", "signal-events", date] as const,
  marketSignalEvents: (date: string) =>
    ["leading-stocks", "market-signal-events", date] as const,
  leadingStockDetail: (code: string) =>
    ["leading-stocks", "detail", code] as const,
  investorTrend: (code: string) =>
    ["leading-stocks", "investors", code] as const,
  minuteCandles: (code: string, date: string) =>
    ["leading-stocks", "minute-candles", code, date] as const,
  indexMinuteCandles: (market: string, date: string) =>
    ["leading-stocks", "index-minute-candles", market, date] as const,
  dailyCandles: (code: string, date: string) =>
    ["leading-stocks", "daily-candles", code, date] as const,
  kospiIndex: ["market", "kospi"] as const,
  kosdaqIndex: ["market", "kosdaq"] as const,
  themeCalendar: (from: string, to: string) =>
    ["themes", "calendar", from, to] as const,
  overseasRanking: ["overseas-leading-stocks", "ranking"] as const,
};

export interface InstanceInfo {
  broker: string;
  env: string;
  label: string;
}

export function useInstanceInfo() {
  return useQuery({
    queryKey: QK.instanceInfo,
    queryFn: () => apiFetch<InstanceInfo>("/api/system/instance"),
    staleTime: Infinity,
    gcTime: Infinity,
  });
}

export function useMarketStatus() {
  return useQuery({
    queryKey: QK.marketStatus,
    queryFn: () => apiFetch<MarketStatus>("/api/market/status"),
    refetchInterval: 10 * 60_000,
  });
}

export const STOCK_SEARCH_MIN_LEN = 2;

export function useStockSearch(query: string) {
  const q = query.trim();
  return useQuery({
    queryKey: QK.stockSearch(q),
    queryFn: () =>
      apiFetch<StockSearchResult[]>(
        `/api/stocks/search?q=${encodeURIComponent(q)}`,
      ),
    enabled: q.length >= STOCK_SEARCH_MIN_LEN,
  });
}

export function useLeadingStockCandidates(minChangeRate: number) {
  return useQuery({
    queryKey: QK.leadingStockCandidates(minChangeRate),
    queryFn: () =>
      apiFetch<CandidateStocksResponse>(
        `/api/leading-stocks/candidates?minChangeRate=${minChangeRate}`,
      ),
    refetchInterval: 5_000,
  });
}

export function useBreakoutRadar(minChangeRate: number) {
  return useQuery({
    queryKey: QK.breakoutRadar(minChangeRate),
    queryFn: () =>
      apiFetch<BreakoutRadarResponse>(
        `/api/leading-stocks/breakout-radar?minChangeRate=${minChangeRate}`,
      ),
    refetchInterval: 5_000,
  });
}

export function useSignalEvents(date: string) {
  const isToday = date === todayStr();
  return useQuery({
    queryKey: QK.signalEvents(date),
    queryFn: () =>
      apiFetch<SignalEventsResponse>(
        `/api/leading-stocks/signal-events?date=${date}`,
      ),
    refetchInterval: isToday ? 5_000 : false,
  });
}

export function useMarketSignalEvents(date: string) {
  const isToday = date === todayStr();
  return useQuery({
    queryKey: QK.marketSignalEvents(date),
    queryFn: () =>
      apiFetch<MarketSignalEventsResponse>(
        `/api/leading-stocks/market-signal-events?date=${date}`,
      ),
    refetchInterval: isToday ? 5_000 : false,
  });
}

export function useKospiIndex() {
  return useQuery({
    queryKey: QK.kospiIndex,
    queryFn: () => apiFetch<KospiIndex>("/api/market/kospi"),
    refetchInterval: 30_000,
  });
}

export function useKosdaqIndex() {
  return useQuery({
    queryKey: QK.kosdaqIndex,
    queryFn: () => apiFetch<MarketIndex>("/api/market/kosdaq"),
    refetchInterval: 30_000,
  });
}

export function useInvestorTrend(code: string | null) {
  return useQuery({
    queryKey: code ? QK.investorTrend(code) : ["leading-stocks", "investors", "null"],
    queryFn: () =>
      apiFetch<InvestorTrendDay[]>(
        `/api/leading-stocks/candidates/${code}/investors`,
      ),
    enabled: code !== null,
    staleTime: 5 * 60_000,
  });
}

export function useMinuteCandles(code: string | null, date: string) {
  return useQuery({
    queryKey: code ? QK.minuteCandles(code, date) : ["leading-stocks", "minute-candles", "null", date],
    queryFn: () =>
      apiFetch<MinuteCandleItem[]>(
        `/api/leading-stocks/candidates/${code}/minute-candles?date=${date}`,
      ),
    enabled: code !== null,
    refetchInterval: date === todayStr() ? 30_000 : false,
  });
}

export function useIndexMinuteCandles(market: MarketType | null, date: string) {
  return useQuery({
    queryKey: market
      ? QK.indexMinuteCandles(market, date)
      : ["leading-stocks", "index-minute-candles", "null", date],
    queryFn: () =>
      apiFetch<IndexMinuteCandleItem[]>(
        `/api/leading-stocks/index/${market}/minute-candles?date=${date}`,
      ),
    enabled: market !== null,
    refetchInterval: date === todayStr() ? 30_000 : false,
  });
}

export function useDailyCandles(code: string | null, date: string) {
  return useQuery({
    queryKey: code ? QK.dailyCandles(code, date) : ["leading-stocks", "daily-candles", "null", date],
    queryFn: () =>
      apiFetch<DailyCandleItem[]>(
        `/api/leading-stocks/candidates/${code}/daily-candles?date=${date}`,
      ),
    enabled: code !== null,
    staleTime: 30_000,
  });
}

export function useLeadingStockDetail(code: string | null) {
  return useQuery({
    queryKey: code
      ? QK.leadingStockDetail(code)
      : ["leading-stocks", "detail", "null"],
    queryFn: () =>
      apiFetch<LeadingStockDetailResponse>(
        `/api/leading-stocks/candidates/${code}`,
      ),
    enabled: code !== null,
  });
}

export function useOverseasRanking() {
  return useQuery({
    queryKey: QK.overseasRanking,
    queryFn: () =>
      apiFetch<OverseasStockRankItem[]>(
        `/api/overseas-leading-stocks/ranking`,
      ),
    refetchInterval: 30_000,
  });
}

export function useThemeCalendar(from: string, to: string) {
  return useQuery({
    queryKey: QK.themeCalendar(from, to),
    queryFn: () =>
      apiFetch<ThemeCalendarResponse>(
        `/api/themes/calendar?from=${from}&to=${to}`,
      ),
    staleTime: 60_000,
  });
}
