import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { todayStr } from "../components/common/DateNavigator";
import type {
  SessionsResponse,
  BreakoutRadarResponse,
  DailyCandleItem,
  LeadersResponse,
  CandidateStocksResponse,
  KospiIndex,
  FuturesQuote,
  FuturesSession,
  FuturesInvestorDay,
  StockInvestorDay,
  StockNewsItem,
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
} from "../types";

export const QK = {
  calendarStatus: (region: MarketRegion) => ["market", "calendar", region] as const,
  stockSearch: (q: string) => ["stocks", "search", q] as const,
  leadingStockCandidates: (minChangeRate: number) =>
    ["leading-stocks", "candidates", minChangeRate] as const,
  leadingStockLeaders: ["leading-stocks", "leaders"] as const,
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
  stockNews: (stockCode: string, exchange: string | null) =>
    ["news", "stock", stockCode, exchange] as const,
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
  overseasRanking: (minChangeRate: number) =>
    ["overseas-leading-stocks", "ranking", minChangeRate] as const,
  overseasDetail: (exchange: string, symbol: string) =>
    ["overseas-leading-stocks", "detail", exchange, symbol] as const,
  overseasMinuteCandles: (exchange: string, symbol: string) =>
    ["overseas-leading-stocks", "minute-candles", exchange, symbol] as const,
  overseasDailyCandles: (exchange: string, symbol: string) =>
    ["overseas-leading-stocks", "daily-candles", exchange, symbol] as const,
};

/** 시장 휴장 상태 — region KR(국내)/US(해외). 토스 장 운영 정보 기반. */
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

/**
 * 첫 화면 주도주 — 거래대금·등락률이 함께 높은 다섯 종목.
 *
 * 후보 목록과 달리 **등락률 인자를 받지 않는다.** 첫 화면은 보는 사람이 목록 화면에
 * 걸어둔 기준과 무관하게 같은 답을 보여야 해서, 서버가 정한 규칙 하나만 쓴다.
 */
export function useLeadingStockLeaders() {
  return useQuery({
    queryKey: QK.leadingStockLeaders,
    queryFn: () => apiFetch<LeadersResponse>("/api/leading-stocks/leaders"),
    refetchInterval: 5_000,
  });
}

/** `mode`는 미로그인 미리보기를 **어느 쪽 근접 순으로 자를지** 정한다.
 *  전부 받는 로그인 쪽에서는 결과가 같다 — 화면이 어차피 다시 정렬한다. */
export function useBreakoutRadar(mode: "resistance" | "support" = "resistance") {
  return useQuery({
    queryKey: [...QK.breakoutRadar, mode],
    queryFn: () =>
      apiFetch<BreakoutRadarResponse>(`/api/leading-stocks/breakout-radar?mode=${mode}`),
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

/** 지수 시그널은 로그인 뒤다 — 미로그인이면 부르지 않는다. 호출해봐야 401이고
 *  폴링 주기마다 반복된다. 종목 시그널(`useSignalEvents`)은 미리보기로 열려 있다. */
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

/** 시그널 발생 시각([at], ISO LocalDateTime) 기준 스냅샷 순매수. 그 시각 스냅샷이 없으면 빈 배열. */
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

/** 첫 화면 "오늘의 수급" — 코스피·코스닥(억원)과 두 지수선물(계약). 로그인 뒤에만 부른다. */
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

/** 최근 [count]일 일별 순매수(외/기/개/기타법인 + 기관 세부) — 토스 Market Indicators. */
export function useMarketInvestorDaily(market: "KOSPI" | "KOSDAQ", count = 10) {
  return useQuery({
    queryKey: QK.marketInvestorDaily(market, count),
    queryFn: () =>
      apiFetch<MarketInvestorDay[]>(`/api/market/${market}/investor/daily?count=${count}`),
    staleTime: 60_000,
  });
}

/** 세션별(오전/오후/마감) 순매수 — 당일 누적 스냅샷 경계 diff. */
export function useMarketInvestorSessions(market: "KOSPI" | "KOSDAQ", date: string) {
  return useQuery({
    queryKey: QK.marketInvestorSessions(market, date),
    queryFn: () =>
      apiFetch<SessionsResponse<MarketInvestorSession>>(
        `/api/market/${market}/investor/sessions?date=${date}`,
      ),
    // 백엔드 순매수 스냅샷 폴러(60초)와 같은 주기 — 더 자주 물어도 같은 값을 받는다.
    refetchInterval: 60_000,
  });
}

/** 시장 지수 캔들(OHLCV) — 토스. interval "1d"는 최근 [count]봉, "1m"은 최근 거래일치. market=null이면 비활성. */
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
    refetchInterval: interval === "1m" ? 30_000 : false,
  });
}

/** 코스피 선물(근월물) 시세 요약 — KIS. 선물·현물·베이시스·괴리율·미결제. */
export function useFuturesQuote(market: MarketType) {
  return useQuery({
    queryKey: QK.futuresQuote(market),
    queryFn: () => apiFetch<FuturesQuote | null>(`/api/market/futures/${market}/quote`),
    refetchInterval: 30_000,
  });
}

/** 코스피 야간선물 시세 — KIS(CM). 현재가·정규장 종가 대비 갭. */
export function useNightFuturesQuote() {
  return useQuery({
    queryKey: QK.nightFuturesQuote,
    queryFn: () => apiFetch<NightFuturesQuote | null>("/api/market/futures/night/quote"),
    refetchInterval: 30_000,
  });
}

/** 코스피 야간선물 캔들 — interval "1d"/"1m"(최근 세션). */
export function useNightFuturesCandles(interval: "1d" | "1m", count = 90) {
  return useQuery({
    queryKey: QK.nightFuturesCandles(interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(
        `/api/market/futures/night/candles?interval=${interval}&count=${count}`,
      ),
    refetchInterval: interval === "1m" ? 30_000 : false,
  });
}

/** 나스닥 종합지수(^IXIC) 시세 — 야후. */
export function useNasdaqIndexQuote() {
  return useQuery({
    queryKey: QK.nasdaqIndexQuote,
    queryFn: () => apiFetch<NasdaqIndexQuote | null>("/api/market/nasdaq/quote"),
    refetchInterval: 30_000,
  });
}

/** 나스닥 종합지수 캔들 — interval "1d"/"1m". */
export function useNasdaqIndexCandles(interval: "1d" | "1m") {
  return useQuery({
    queryKey: QK.nasdaqIndexCandles(interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(`/api/market/nasdaq/candles?interval=${interval}`),
    refetchInterval: interval === "1m" ? 30_000 : false,
  });
}

/** 나스닥100 선물(NQ=F) 시세 — 야후. 현물과 달리 우리 장중에도 돈다. */
export function useNasdaqFuturesQuote() {
  return useQuery({
    queryKey: QK.nasdaqFuturesQuote,
    queryFn: () => apiFetch<NasdaqFuturesQuote | null>("/api/market/futures/nasdaq/quote"),
    refetchInterval: 30_000,
  });
}

/** 나스닥 선물 캔들 — interval "1d"/"1m". */
export function useNasdaqFuturesCandles(interval: "1d" | "1m") {
  return useQuery({
    queryKey: QK.nasdaqFuturesCandles(interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(`/api/market/futures/nasdaq/candles?interval=${interval}`),
    refetchInterval: interval === "1m" ? 30_000 : false,
  });
}

/** 원달러·WTI 시세 — 야후. 상단 스트립이 한 칸에 둘 다 그린다. */
export function useMacroQuotes() {
  return useQuery({
    queryKey: QK.macroQuotes,
    queryFn: () => apiFetch<MacroQuotes>("/api/market/macro/quotes"),
    refetchInterval: 30_000,
  });
}

/** 매크로 캔들 — interval "1d"/"1m". */
export function useMacroCandles(target: MacroTarget, interval: "1d" | "1m") {
  return useQuery({
    queryKey: QK.macroCandles(target, interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(
        `/api/market/macro/candles?target=${target}&interval=${interval}`,
      ),
    refetchInterval: interval === "1m" ? 30_000 : false,
  });
}

/** 코스피 선물 세션별(오전/오후/마감) 투자자 순매수 — 계약. */
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

/** 종목 최근 N일 일별 순매수(억원) — 키움 ka10059. 국내 종목만. */
export function useStockInvestorDaily(stockCode: string, count = 10) {
  return useQuery({
    queryKey: QK.stockInvestorDaily(stockCode, count),
    queryFn: () =>
      apiFetch<StockInvestorDay[]>(`/api/stocks/${stockCode}/investor/daily?count=${count}`),
    staleTime: 60_000,
  });
}

/** 종목 관련 뉴스·공시 — KIS. [exchange]가 null이면 국내, 아니면 해외(NAS/NYS/AMS). */
export function useStockNews(stockCode: string, exchange: string | null) {
  return useQuery({
    queryKey: QK.stockNews(stockCode, exchange),
    queryFn: () =>
      apiFetch<StockNewsItem[]>(
        exchange
          ? `/api/news/stock/${stockCode}?exchange=${exchange}`
          : `/api/news/stock/${stockCode}`,
      ),
    refetchInterval: 60_000,
  });
}

/** 코스피 선물 일별 투자자 순매수(계약) — 스냅샷을 쌓은 날만 온다. */
export function useFuturesInvestorDaily(market: MarketType, count = 10) {
  return useQuery({
    queryKey: QK.futuresInvestorDaily(market, count),
    queryFn: () =>
      apiFetch<FuturesInvestorDay[]>(`/api/market/futures/${market}/investor/daily?count=${count}`),
    staleTime: 60_000,
  });
}

/** 지수선물(코스피200/코스닥150) 근월물 캔들 — KIS. interval "1d"/"1m". */
export function useFuturesCandles(market: MarketType, interval: "1d" | "1m", count = 90) {
  return useQuery({
    queryKey: QK.futuresCandles(market, interval),
    queryFn: () =>
      apiFetch<MarketCandleItem[]>(
        `/api/market/futures/${market}/candles?interval=${interval}&count=${count}`,
      ),
    refetchInterval: interval === "1m" ? 30_000 : false,
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

/** 첫 화면 해외 주도주 — 국내와 같은 규칙, 같은 칸 수. */
export function useOverseasLeaders() {
  return useQuery({
    queryKey: QK.overseasLeaders,
    queryFn: () => apiFetch<OverseasStockRankItem[]>("/api/overseas-leading-stocks/leaders"),
    refetchInterval: 15_000,
  });
}

export function useOverseasRanking(minChangeRate: number) {
  return useQuery({
    queryKey: QK.overseasRanking(minChangeRate),
    queryFn: () =>
      apiFetch<OverseasStockRankItem[]>(
        `/api/overseas-leading-stocks/ranking?minChangeRate=${minChangeRate}`,
      ),
    refetchInterval: 15_000,
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
    refetchInterval: 60_000,
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
