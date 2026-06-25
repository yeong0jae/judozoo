import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { todayStr } from "../components/common/DateNavigator";
import type {
  AccountBalance,
  BreakoutRadarResponse,
  DailyCandleItem,
  CandidateStocksResponse,
  DailyReport,
  DailyTrading,
  Holding,
  InvestorTrendDay,
  KospiIndex,
  MarketIndex,
  LeadingStockDetailResponse,
  MinuteCandleItem,
  MarketStatus,
  RegimeDaily,
  RegimeSnapshot,
  SignalEventsResponse,
  StockPriceResult,
  StockSearchResult,
  ThemeCalendarResponse,
  TradingDetail,
  TradingSummary,
} from "../types";

export const QK = {
  instanceInfo: ["system", "instance"] as const,
  marketStatus: ["market", "status"] as const,
  accountBalance: ["account", "balance"] as const,
  holdings: ["account", "holdings"] as const,
  activeCommands: ["trading", "active"] as const,
  todayClosed: ["trading", "today"] as const,
  commandDetail: (id: number) => ["trading", "detail", id] as const,
  stockSearch: (q: string) => ["stocks", "search", q] as const,
  stockPrice: (code: string) => ["stocks", "price", code] as const,
  dailyReport: (date: string) => ["reports", "daily", date] as const,
  leadingStockCandidates: (minChangeRate: number) =>
    ["leading-stocks", "candidates", minChangeRate] as const,
  breakoutRadar: (minChangeRate: number) =>
    ["leading-stocks", "breakout-radar", minChangeRate] as const,
  signalEvents: (date: string) =>
    ["leading-stocks", "signal-events", date] as const,
  leadingStockDetail: (code: string) =>
    ["leading-stocks", "detail", code] as const,
  investorTrend: (code: string) =>
    ["leading-stocks", "investors", code] as const,
  minuteCandles: (code: string) =>
    ["leading-stocks", "minute-candles", code] as const,
  dailyCandles: (code: string) =>
    ["leading-stocks", "daily-candles", code] as const,
  kospiIndex: ["market", "kospi"] as const,
  kosdaqIndex: ["market", "kosdaq"] as const,
  regime: ["market", "regime"] as const,
  regimeDaily: ["market", "regime", "daily"] as const,
  themeCalendar: (from: string, to: string) =>
    ["themes", "calendar", from, to] as const,
};

export interface InstanceInfo {
  broker: string;
  env: string;
  label: string;
}

export function useInstanceInfo() {
  // 부팅 시점에 결정되어 런타임 중 변경 안 됨 → 영구 캐시.
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

export function useRegime() {
  return useQuery({
    queryKey: QK.regime,
    queryFn: () => apiFetch<RegimeSnapshot | null>("/api/market/regime"),
  });
}

export function useRegimeDaily() {
  return useQuery({
    queryKey: QK.regimeDaily,
    queryFn: () => apiFetch<RegimeDaily[]>("/api/market/regime/daily"),
    refetchInterval: 30_000, // 오늘 막대(종가)가 본장 중 갱신되도록
  });
}

export function useAccountBalance() {
  return useQuery({
    queryKey: QK.accountBalance,
    queryFn: () => apiFetch<AccountBalance>("/api/account/balance"),
  });
}

export function useHoldings() {
  return useQuery({
    queryKey: QK.holdings,
    queryFn: () => apiFetch<Holding[]>("/api/account/holdings"),
  });
}

export function useActiveCommands() {
  return useQuery({
    queryKey: QK.activeCommands,
    queryFn: () =>
      apiFetch<TradingSummary[]>("/api/trading?status=active"),
  });
}

export function useTodayClosed() {
  return useQuery({
    queryKey: QK.todayClosed,
    queryFn: () =>
      apiFetch<DailyTrading[]>("/api/trading?status=today"),
  });
}

export function useCommandDetail(id: number | null) {
  return useQuery({
    queryKey: id ? QK.commandDetail(id) : ["trading", "detail", "null"],
    queryFn: () => apiFetch<TradingDetail>(`/api/trading/${id}`),
    enabled: id !== null,
  });
}

// 백엔드 검색은 로컬 종목 카탈로그(이름/코드 부분일치)라 2자 이상이면 질의한다.
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

export function useStockPrice(code: string | null) {
  return useQuery({
    queryKey: code ? QK.stockPrice(code) : ["stocks", "price", "null"],
    queryFn: () =>
      apiFetch<StockPriceResult>(`/api/stocks/${code}/price`),
    enabled: code !== null,
  });
}

export function useDailyReport(date: string) {
  return useQuery({
    queryKey: QK.dailyReport(date),
    queryFn: () =>
      apiFetch<DailyReport[]>(`/api/reports/daily?date=${date}`),
  });
}

// === 주도주 (Leading Stocks) ===
// 백엔드의 candidateStocks 캐시(5s TTL)와 같은 호흡으로 폴링
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
    refetchInterval: isToday ? 5_000 : false, // 과거 날짜는 정적 — 폴링 안 함
  });
}

export function useKospiIndex() {
  return useQuery({
    queryKey: QK.kospiIndex,
    queryFn: () => apiFetch<KospiIndex>("/api/market/kospi"),
    refetchInterval: 30_000, // 헤더용 — 30초마다
  });
}

export function useKosdaqIndex() {
  return useQuery({
    queryKey: QK.kosdaqIndex,
    queryFn: () => apiFetch<MarketIndex>("/api/market/kosdaq"),
    refetchInterval: 30_000, // 헤더용 — 30초마다
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
    staleTime: 5 * 60_000, // 일자별 데이터 — 5분 신선도
  });
}

export function useMinuteCandles(code: string | null) {
  return useQuery({
    queryKey: code ? QK.minuteCandles(code) : ["leading-stocks", "minute-candles", "null"],
    queryFn: () =>
      apiFetch<MinuteCandleItem[]>(
        `/api/leading-stocks/candidates/${code}/minute-candles`,
      ),
    enabled: code !== null,
    refetchInterval: 30_000, // 서버 분봉 캐시 30초와 맞춤
  });
}

export function useDailyCandles(code: string | null) {
  return useQuery({
    queryKey: code ? QK.dailyCandles(code) : ["leading-stocks", "daily-candles", "null"],
    queryFn: () =>
      apiFetch<DailyCandleItem[]>(
        `/api/leading-stocks/candidates/${code}/daily-candles`,
      ),
    enabled: code !== null,
    staleTime: 30_000, // 일봉 — 자주 안 바뀜
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

export function useThemeCalendar(from: string, to: string) {
  return useQuery({
    queryKey: QK.themeCalendar(from, to),
    queryFn: () =>
      apiFetch<ThemeCalendarResponse>(
        `/api/themes/calendar?from=${from}&to=${to}`,
      ),
    staleTime: 60_000, // 일자별 적재 데이터 — 자주 안 바뀜
  });
}
