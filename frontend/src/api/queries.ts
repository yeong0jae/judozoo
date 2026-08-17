import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { todayStr } from "../components/common/DateNavigator";
import type {
  BreakoutRadarResponse,
  DailyCandleItem,
  CandidateStocksResponse,
  KospiIndex,
  FuturesQuote,
  FuturesSession,
  FuturesInvestorDay,
  StockInvestorDay,
  StockNewsItem,
  MacroQuotes,
  MacroTarget,
  NasdaqIndexQuote,
  NightFuturesQuote,
  MarketIndex,
  LeadingStockDetailResponse,
  MinuteCandleItem,
  IndexMinuteCandleItem,
  MarketSignalEventsResponse,
  MarketInvestorNetBuyItem,
  MarketCandleItem,
  MarketInvestorDay,
  MarketInvestorSession,
  ProgramSession,
  ProgramDay,
  MarketType,
  MarketRegion,
  CalendarStatus,
  OverseasStockDetailResponse,
  OverseasStockRankItem,
  SignalEventsResponse,
  StockSearchResult,
  StockQuote,
  WatchTheme,
  ThemeCalendarResponse,
} from "../types";

export const QK = {
  calendarStatus: (region: MarketRegion) => ["market", "calendar", region] as const,
  stockSearch: (q: string) => ["stocks", "search", q] as const,
  leadingStockCandidates: (minChangeRate: number) =>
    ["leading-stocks", "candidates", minChangeRate] as const,
  breakoutRadar: (minChangeRate: number) =>
    ["leading-stocks", "breakout-radar", minChangeRate] as const,
  signalEvents: (date: string) =>
    ["leading-stocks", "signal-events", date] as const,
  marketSignalEvents: (date: string) =>
    ["leading-stocks", "market-signal-events", date] as const,
  marketCloseSnapshots: (date: string) =>
    ["leading-stocks", "market-close-snapshots", date] as const,
  overseasIndexCloseSnapshots: (date: string) =>
    ["overseas-leading-stocks", "index-close-snapshots", date] as const,
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
  kosdaqIndex: ["market", "kosdaq"] as const,
  marketInvestorDaily: (market: string, count: number) =>
    ["market", market, "investor", "daily", count] as const,
  marketInvestorSessions: (market: string, date: string) =>
    ["market", market, "investor", "sessions", date] as const,
  marketProgramSessions: (market: string, date: string) =>
    ["market", market, "program", "sessions", date] as const,
  marketProgramDaily: (market: string, count: number) =>
    ["market", market, "program", "daily", count] as const,
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
  themeCalendar: (from: string, to: string) =>
    ["themes", "calendar", from, to] as const,
  overseasRanking: (minChangeRate: number) =>
    ["overseas-leading-stocks", "ranking", minChangeRate] as const,
  overseasDetail: (exchange: string, symbol: string) =>
    ["overseas-leading-stocks", "detail", exchange, symbol] as const,
  overseasMinuteCandles: (exchange: string, symbol: string) =>
    ["overseas-leading-stocks", "minute-candles", exchange, symbol] as const,
  overseasDailyCandles: (exchange: string, symbol: string) =>
    ["overseas-leading-stocks", "daily-candles", exchange, symbol] as const,
  issues: (date: string) => ["issues", date] as const,
  watchThemes: ["watch-themes"] as const,
  watchThemeQuotes: (themeId: number) => ["watch-themes", themeId, "quotes"] as const,
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

/** 세션별(오전/오후/막판) 순매수 — 당일 누적 스냅샷 경계 diff. */
export function useMarketInvestorSessions(market: "KOSPI" | "KOSDAQ", date: string) {
  return useQuery({
    queryKey: QK.marketInvestorSessions(market, date),
    queryFn: () =>
      apiFetch<MarketInvestorSession[]>(`/api/market/${market}/investor/sessions?date=${date}`),
    // 백엔드 순매수 스냅샷 폴러(30초)와 같은 주기 — 진행 중 세션 값이 30초마다 최신화된다.
    refetchInterval: 30_000,
  });
}

/** 프로그램 세션별 순매수 — ka90010 당일 누적 스냅샷 경계 diff. 폴러(2분)와 같은 주기로 폴링. */
export function useMarketProgramSessions(market: "KOSPI" | "KOSDAQ", date: string) {
  return useQuery({
    queryKey: QK.marketProgramSessions(market, date),
    queryFn: () =>
      apiFetch<ProgramSession[]>(`/api/market/${market}/program/sessions?date=${date}`),
    refetchInterval: 120_000,
  });
}

/** 최근 N일 프로그램 순매수(억원) — ka90010 일별. */
export function useMarketProgramDaily(market: "KOSPI" | "KOSDAQ", count = 10) {
  return useQuery({
    queryKey: QK.marketProgramDaily(market, count),
    queryFn: () =>
      apiFetch<ProgramDay[]>(`/api/market/${market}/program/daily?count=${count}`),
    staleTime: 60_000,
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

/** 코스피 선물 세션별(오전/오후/막판) 투자자 순매수 — 계약. */
export function useFuturesInvestorSessions(market: MarketType, date: string) {
  return useQuery({
    queryKey: QK.futuresInvestorSessions(market, date),
    queryFn: () =>
      apiFetch<FuturesSession[]>(`/api/market/futures/${market}/investor/sessions?date=${date}`),
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

/** 테마 뉴스 항목 — 종목 뉴스에 어느 종목에서 온 건지 종목명을 붙인 것. */
export type ThemeNewsItem = StockNewsItem & { stockName: string };

/**
 * 테마 통합 뉴스 — 테마의 국내 종목별 뉴스를 병렬 조회해 최신순으로 병합한다(해외 종목은 아직 제외).
 * 같은 뉴스가 여러 종목에 등록될 수 있어 seqNo로 중복을 제거한다. 캐시는 종목 뉴스와 공유된다.
 */
export function useThemeNews(
  stocks: { stockCode: string; stockName: string; exchange: string | null }[],
) {
  const domestic = stocks.filter((s) => s.exchange === null);
  const results = useQueries({
    queries: domestic.map((s) => ({
      queryKey: QK.stockNews(s.stockCode, null),
      queryFn: () => apiFetch<StockNewsItem[]>(`/api/news/stock/${s.stockCode}`),
      staleTime: 60_000,
    })),
  });
  const isLoading = results.length > 0 && results.some((r) => r.isLoading);
  const seen = new Set<string>();
  const data: ThemeNewsItem[] = [];
  results.forEach((r, i) => {
    (r.data ?? []).forEach((n) => {
      if (seen.has(n.seqNo)) return;
      seen.add(n.seqNo);
      data.push({ ...n, stockName: domestic[i].stockName });
    });
  });
  data.sort((a, b) => (a.publishedAt < b.publishedAt ? 1 : a.publishedAt > b.publishedAt ? -1 : 0));
  return { data, isLoading };
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

// === 관심 테마 ===

export function useWatchThemes() {
  return useQuery({
    queryKey: QK.watchThemes,
    queryFn: () => apiFetch<WatchTheme[]>("/api/watch-themes"),
  });
}

/** 선택한 테마의 종목 시세만 조회 — 전체를 매번 부르면 키움 rate limit에 걸린다. */
export function useWatchThemeQuotes(themeId: number, enabled: boolean) {
  return useQuery({
    queryKey: QK.watchThemeQuotes(themeId),
    queryFn: () => apiFetch<StockQuote[]>(`/api/watch-themes/${themeId}/quotes`),
    enabled,
    refetchInterval: 5_000,
  });
}

export function useCreateWatchTheme() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) =>
      apiFetch<WatchTheme>("/api/watch-themes", {
        method: "POST",
        body: JSON.stringify({ name }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: QK.watchThemes }),
  });
}

export function useRenameWatchTheme() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ themeId, name }: { themeId: number; name: string }) =>
      apiFetch<WatchTheme>(`/api/watch-themes/${themeId}`, {
        method: "PATCH",
        body: JSON.stringify({ name }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: QK.watchThemes }),
  });
}

export function useDeleteWatchTheme() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (themeId: number) =>
      apiFetch<void>(`/api/watch-themes/${themeId}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: QK.watchThemes }),
  });
}

export function useAddWatchStock() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      themeId,
      stockCode,
      stockName,
      exchange,
    }: {
      themeId: number;
      stockCode: string;
      stockName: string;
      exchange: string | null;
    }) =>
      apiFetch<WatchTheme>(`/api/watch-themes/${themeId}/stocks`, {
        method: "POST",
        body: JSON.stringify({ stockCode, stockName, exchange }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: QK.watchThemes }),
  });
}

export function useRemoveWatchStock() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ themeId, stockCode }: { themeId: number; stockCode: string }) =>
      apiFetch<WatchTheme>(`/api/watch-themes/${themeId}/stocks/${stockCode}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: QK.watchThemes }),
  });
}

export function useReorderWatchThemes() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (themeIds: number[]) =>
      apiFetch<void>("/api/watch-themes/order", {
        method: "PATCH",
        body: JSON.stringify({ themeIds }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: QK.watchThemes }),
  });
}

export function useReorderWatchStocks() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ themeId, stockCodes }: { themeId: number; stockCodes: string[] }) =>
      apiFetch<WatchTheme>(`/api/watch-themes/${themeId}/stocks/order`, {
        method: "PATCH",
        body: JSON.stringify({ stockCodes }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: QK.watchThemes }),
  });
}
