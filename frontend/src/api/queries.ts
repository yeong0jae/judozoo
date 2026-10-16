import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { todayStr } from "../components/common/DateNavigator";
import type {
  SessionsResponse,
  BreakoutRadarResponse,
  DailyCandleItem,
  LeadersResponse,
  LeaderCalendarResponse,
  LeaderTimelineResponse,
  CandidateStocksResponse,
  KospiIndex,
  FuturesQuote,
  FuturesSession,
  FuturesInvestorDay,
  StockInvestorDay,
  MacroQuotes,
  MacroTarget,
  NasdaqFuturesQuote,
  NasdaqIndexQuote,
  NightFuturesQuote,
  MarketIndex,
  LeadingStockDetailResponse,
  MinuteCandleItem,
  IndexMinuteCandleItem,
  MarketSignalEventsResponse,
  MarketInvestorNetBuyItem,
  TodayNetItem,
  MarketCandleItem,
  MarketInvestorDay,
  MarketInvestorSession,
  MarketType,
  MarketRegion,
  CalendarStatus,
  OverseasStockDetailResponse,
  OverseasStockRankItem,
  SignalEventsResponse,
  StockSearchResult,
  InsightReasonItem,
} from "../types";

export const QK = {
  calendarStatus: (region: MarketRegion) => ["market", "calendar", region] as const,
  stockSearch: (q: string) => ["stocks", "search", q] as const,
  leadingStockCandidates: (minChangeRate: number) =>
    ["leading-stocks", "candidates", minChangeRate] as const,
  leadingStockLeaders: ["leading-stocks", "leaders"] as const,
  leaderCalendar: (month: string) => ["leader-calendar", month] as const,
  leaderTimeline: (market: string, date: string) => ["leader-timeline", market, date] as const,
  breakoutRadar: ["leading-stocks", "breakout-radar"] as const,
  signalEvents: (date: string) =>
    ["leading-stocks", "signal-events", date] as const,
  marketSignalEvents: (date: string) =>
    ["leading-stocks", "market-signal-events", date] as const,
  marketInvestorNetBuy: ["leading-stocks", "market-investor-net-buy"] as const,
  marketInvestorNetBuyAt: (at: string) =>
    ["leading-stocks", "market-investor-net-buy", "at", at] as const,
  leadingStockDetail: (code: string) =>
    ["leading-stocks", "detail", code] as const,
  minuteCandles: (code: string, date: string) =>
    ["leading-stocks", "minute-candles", code, date] as const,
  indexMinuteCandles: (market: string, date: string) =>
    ["leading-stocks", "index-minute-candles", market, date] as const,
  dailyCandles: (code: string, date: string) =>
    ["leading-stocks", "daily-candles", code, date] as const,
  kospiIndex: ["market", "kospi"] as const,
  todayNets: ["market", "investor", "today"] as const,
  kosdaqIndex: ["market", "kosdaq"] as const,
  marketInvestorDaily: (market: string, count: number) =>
    ["market", market, "investor", "daily", count] as const,
  marketInvestorSessions: (market: string, date: string) =>
    ["market", market, "investor", "sessions", date] as const,
  marketCandles: (market: string, interval: string) =>
    ["market", market, "candles", interval] as const,
  futuresQuote: (market: MarketType) => ["market", "futures", market, "quote"] as const,
  futuresCandles: (market: MarketType, interval: string) =>
    ["market", "futures", market, "candles", interval] as const,
  futuresInvestorSessions: (market: MarketType, date: string) =>
    ["market", "futures", market, "investor", "sessions", date] as const,
  futuresInvestorDaily: (market: MarketType, count: number) =>
    ["market", "futures", market, "investor", "daily", count] as const,
  stockInvestorDaily: (stockCode: string, count: number) =>
    ["stocks", stockCode, "investor", "daily", count] as const,
  nightFuturesQuote: ["market", "futures", "night", "quote"] as const,
  nightFuturesCandles: (interval: string) =>
    ["market", "futures", "night", "candles", interval] as const,
  macroQuotes: ["market", "macro", "quotes"] as const,
  macroCandles: (target: string, interval: string) =>
    ["market", "macro", "candles", target, interval] as const,
  nasdaqIndexQuote: ["market", "nasdaq", "quote"] as const,
  nasdaqIndexCandles: (interval: string) =>
    ["market", "nasdaq", "candles", interval] as const,
  nasdaqFuturesQuote: ["market", "futures", "nasdaq", "quote"] as const,
  nasdaqFuturesCandles: (interval: string) =>
    ["market", "futures", "nasdaq", "candles", interval] as const,
  overseasLeaders: ["overseas-leading-stocks", "leaders"] as const,
  insightReasons: (market: "kr" | "us") => ["insight", "reasons", market] as const,
  overseasCandidates: (minChangeRate: number) =>
    ["overseas-leading-stocks", "candidates", minChangeRate] as const,
  overseasDetail: (exchange: string, symbol: string) =>
    ["overseas-leading-stocks", "detail", exchange, symbol] as const,
  overseasMinuteCandles: (exchange: string, symbol: string) =>
    ["overseas-leading-stocks", "minute-candles", exchange, symbol] as const,
  overseasDailyCandles: (exchange: string, symbol: string) =>
    ["overseas-leading-stocks", "daily-candles", exchange, symbol] as const,
};

export function useMarketCalendarStatus(region: MarketRegion) {
  return useQuery({
    queryKey: QK.calendarStatus(region),
    queryFn: () => apiFetch<CalendarStatus>(`/api/market/calendar/status?region=${region}`),
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

export const POOL_REFRESH_MS = 15_000;

export const LIVE_REFRESH_MS = 5_000;

export function useLeadingStockCandidates(minChangeRate: number) {
  return useQuery({
    queryKey: QK.leadingStockCandidates(minChangeRate),
    queryFn: () =>
      apiFetch<CandidateStocksResponse>(
        `/api/leading-stocks/candidates?minChangeRate=${minChangeRate}`,
      ),
    refetchInterval: POOL_REFRESH_MS,
  });
}

export function useLeadingStockLeaders() {
  return useQuery({
    queryKey: QK.leadingStockLeaders,
    queryFn: () => apiFetch<LeadersResponse>("/api/leading-stocks/leaders"),
    refetchInterval: POOL_REFRESH_MS,
  });
}

export function useLeaderCalendar(month: string, live: boolean) {
  return useQuery({
    queryKey: QK.leaderCalendar(month),
    queryFn: () => apiFetch<LeaderCalendarResponse>(`/api/leader-calendar?month=${month}`),
    refetchInterval: live ? 60_000 : false,
  });
}

export function useLeaderTimeline(market: "kr" | "us", date: string, live: boolean) {
  return useQuery({
    queryKey: QK.leaderTimeline(market, date),
    queryFn: () => apiFetch<LeaderTimelineResponse>(`/api/leader-timeline?market=${market}&date=${date}`),
    refetchInterval: live ? 60_000 : false,
  });
}

export function useBreakoutRadar(mode: "resistance" | "support" = "resistance") {
  return useQuery({
    queryKey: [...QK.breakoutRadar, mode],
    queryFn: () =>
      apiFetch<BreakoutRadarResponse>(`/api/leading-stocks/breakout-radar?mode=${mode}`),
    refetchInterval: LIVE_REFRESH_MS,
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
    refetchInterval: isToday ? LIVE_REFRESH_MS : false,
  });
}

export function useMarketSignalEvents(date: string, enabled = true) {
  const isToday = date === todayStr();
  return useQuery({
    queryKey: QK.marketSignalEvents(date),
    queryFn: () =>
      apiFetch<MarketSignalEventsResponse>(
        `/api/leading-stocks/market-signal-events?date=${date}`,
      ),
    enabled,
    refetchInterval: isToday ? 5_000 : false,
  });
}

export function useMarketInvestorNetBuy(enabled: boolean) {
  return useQuery({
    queryKey: QK.marketInvestorNetBuy,
    queryFn: () =>
      apiFetch<MarketInvestorNetBuyItem[]>(
        "/api/leading-stocks/market/investor-net-buy",
      ),
    enabled,
    refetchInterval: 30_000,
  });
}

export function useMarketInvestorNetBuyAt(at: string | null, enabled: boolean) {
  return useQuery({
    queryKey: at ? QK.marketInvestorNetBuyAt(at) : ["leading-stocks", "market-investor-net-buy", "at", "null"],
    queryFn: () =>
      apiFetch<MarketInvestorNetBuyItem[]>(
        `/api/leading-stocks/market/investor-net-buy?at=${encodeURIComponent(at as string)}`,
      ),
    enabled: enabled && at !== null,
  });
}

export function useTodayNets(enabled: boolean) {
  return useQuery({
    queryKey: QK.todayNets,
    queryFn: () => apiFetch<TodayNetItem[]>("/api/market/investor/today"),
    enabled,
    refetchInterval: 30_000,
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

export function useMarketInvestorDaily(market: "KOSPI" | "KOSDAQ", count = 10) {
  return useQuery({
    queryKey: QK.marketInvestorDaily(market, count),
    queryFn: () =>
      apiFetch<MarketInvestorDay[]>(`/api/market/${market}/investor/daily?count=${count}`),
    staleTime: 60_000,
  });
}

export function useMarketInvestorSessions(market: "KOSPI" | "KOSDAQ", date: string) {
  return useQuery({
    queryKey: QK.marketInvestorSessions(market, date),
    queryFn: () =>
      apiFetch<SessionsResponse<MarketInvestorSession>>(
        `/api/market/${market}/investor/sessions?date=${date}`,
      ),
    refetchInterval: 60_000,
  });
}

export function useMarketCandles(
  market: "KOSPI" | "KOSDAQ" | null,
  interval: "1d" | "1m",
  count = 90,
) {
  return useQuery({
    queryKey: QK.marketCandles(market ?? "none", interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(
        `/api/market/${market}/candles?interval=${interval}&count=${count}`,
      ),
    enabled: market !== null,
    refetchInterval: interval === "1m" ? 60_000 : false,
  });
}

export function useFuturesQuote(market: MarketType) {
  return useQuery({
    queryKey: QK.futuresQuote(market),
    queryFn: () => apiFetch<FuturesQuote | null>(`/api/market/futures/${market}/quote`),
    refetchInterval: 30_000,
  });
}

export function useNightFuturesQuote() {
  return useQuery({
    queryKey: QK.nightFuturesQuote,
    queryFn: () => apiFetch<NightFuturesQuote | null>("/api/market/futures/night/quote"),
    refetchInterval: 30_000,
  });
}

export function useNightFuturesCandles(interval: "1d" | "1m", count = 90) {
  return useQuery({
    queryKey: QK.nightFuturesCandles(interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(
        `/api/market/futures/night/candles?interval=${interval}&count=${count}`,
      ),
    refetchInterval: interval === "1m" ? 60_000 : false,
  });
}

export function useNasdaqIndexQuote() {
  return useQuery({
    queryKey: QK.nasdaqIndexQuote,
    queryFn: () => apiFetch<NasdaqIndexQuote | null>("/api/market/nasdaq/quote"),
    refetchInterval: 30_000,
  });
}

export function useNasdaqIndexCandles(interval: "1d" | "1m") {
  return useQuery({
    queryKey: QK.nasdaqIndexCandles(interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(`/api/market/nasdaq/candles?interval=${interval}`),
    refetchInterval: interval === "1m" ? 60_000 : false,
  });
}

export function useNasdaqFuturesQuote() {
  return useQuery({
    queryKey: QK.nasdaqFuturesQuote,
    queryFn: () => apiFetch<NasdaqFuturesQuote | null>("/api/market/futures/nasdaq/quote"),
    refetchInterval: 30_000,
  });
}

export function useNasdaqFuturesCandles(interval: "1d" | "1m") {
  return useQuery({
    queryKey: QK.nasdaqFuturesCandles(interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(`/api/market/futures/nasdaq/candles?interval=${interval}`),
    refetchInterval: interval === "1m" ? 60_000 : false,
  });
}

export function useMacroQuotes() {
  return useQuery({
    queryKey: QK.macroQuotes,
    queryFn: () => apiFetch<MacroQuotes>("/api/market/macro/quotes"),
    refetchInterval: 30_000,
  });
}

export function useMacroCandles(target: MacroTarget, interval: "1d" | "1m") {
  return useQuery({
    queryKey: QK.macroCandles(target, interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(
        `/api/market/macro/candles?target=${target}&interval=${interval}`,
      ),
    refetchInterval: interval === "1m" ? 60_000 : false,
  });
}

export function useFuturesInvestorSessions(market: MarketType, date: string) {
  return useQuery({
    queryKey: QK.futuresInvestorSessions(market, date),
    queryFn: () =>
      apiFetch<SessionsResponse<FuturesSession>>(
        `/api/market/futures/${market}/investor/sessions?date=${date}`,
      ),
    refetchInterval: 60_000,
  });
}

export function useStockInvestorDaily(stockCode: string, count = 10) {
  return useQuery({
    queryKey: QK.stockInvestorDaily(stockCode, count),
    queryFn: () =>
      apiFetch<StockInvestorDay[]>(`/api/stocks/${stockCode}/investor/daily?count=${count}`),
    staleTime: 60_000,
  });
}

export function useFuturesInvestorDaily(market: MarketType, count = 10) {
  return useQuery({
    queryKey: QK.futuresInvestorDaily(market, count),
    queryFn: () =>
      apiFetch<FuturesInvestorDay[]>(`/api/market/futures/${market}/investor/daily?count=${count}`),
    staleTime: 60_000,
  });
}

export function useFuturesCandles(market: MarketType, interval: "1d" | "1m", count = 90) {
  return useQuery({
    queryKey: QK.futuresCandles(market, interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(
        `/api/market/futures/${market}/candles?interval=${interval}&count=${count}`,
      ),
    refetchInterval: interval === "1m" ? 60_000 : false,
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

export function useOverseasLeaders() {
  return useQuery({
    queryKey: QK.overseasLeaders,
    queryFn: () => apiFetch<OverseasStockRankItem[]>("/api/overseas-leading-stocks/leaders"),
    refetchInterval: POOL_REFRESH_MS,
  });
}

export function useOverseasCandidates(minChangeRate: number) {
  return useQuery({
    queryKey: QK.overseasCandidates(minChangeRate),
    queryFn: () =>
      apiFetch<OverseasStockRankItem[]>(
        `/api/overseas-leading-stocks/candidates?minChangeRate=${minChangeRate}`,
      ),
    refetchInterval: POOL_REFRESH_MS,
  });
}

export function useOverseasStockDetail(
  exchange: string | null,
  symbol: string | null,
) {
  const enabled = exchange !== null && symbol !== null;
  return useQuery({
    queryKey: enabled
      ? QK.overseasDetail(exchange, symbol)
      : ["overseas-leading-stocks", "detail", "null"],
    queryFn: () =>
      apiFetch<OverseasStockDetailResponse>(
        `/api/overseas-leading-stocks/${exchange}/${symbol}`,
      ),
    enabled,
    staleTime: 30_000,
  });
}

export function useOverseasMinuteCandles(
  exchange: string | null,
  symbol: string | null,
) {
  const enabled = exchange !== null && symbol !== null;
  return useQuery({
    queryKey: enabled
      ? QK.overseasMinuteCandles(exchange, symbol)
      : ["overseas-leading-stocks", "minute-candles", "null"],
    queryFn: () =>
      apiFetch<MinuteCandleItem[]>(
        `/api/overseas-leading-stocks/${exchange}/${symbol}/minute-candles`,
      ),
    enabled,
    refetchInterval: 30_000,
  });
}

export function useOverseasDailyCandles(
  exchange: string | null,
  symbol: string | null,
) {
  const enabled = exchange !== null && symbol !== null;
  return useQuery({
    queryKey: enabled
      ? QK.overseasDailyCandles(exchange, symbol)
      : ["overseas-leading-stocks", "daily-candles", "null"],
    queryFn: () =>
      apiFetch<DailyCandleItem[]>(
        `/api/overseas-leading-stocks/${exchange}/${symbol}/daily-candles`,
      ),
    enabled,
    staleTime: 30_000,
  });
}

export function useInsightReasons(market: "kr" | "us") {
  return useQuery({
    queryKey: QK.insightReasons(market),
    queryFn: () => apiFetch<InsightReasonItem[]>(`/api/insight/reasons?market=${market}`),
    select: (items) => new Map(items.map((r) => [r.code, r])),
    refetchInterval: 60_000,
  });
}
