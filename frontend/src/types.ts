// === REST envelope ===
export interface ApiResponse<T> {
  code: string;
  status: number;
  data: T | null;
}

// === REST responses ===
export interface MarketStatus {
  isHoliday: boolean;
  tradingHoursOpen: boolean;
}

export interface StockSearchResult {
  stockCode: string;
  stockName: string;
}

// === 시장 지수 ===

export interface MarketIndex {
  currentValue: number;
  changeRate: number; // 단위: % (양수=상승)
}

export type KospiIndex = MarketIndex;

// === 주도주 (Leading Stocks) ===

export interface CandidateStockItem {
  rank: number;
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number;
  accumulatedTradingValue: number;
  themes: string[];
  themeCount: number;
}

export interface CandidateStocksResponse {
  queriedAt: string;
  totalCount: number;
  stocks: CandidateStockItem[];
}

// === 1분봉 캔들 ===
export interface MinuteCandleItem {
  time: string; // ISO LocalDateTime (KST 벽시계)
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  tradingValue: number;
}

// === 돌파 임박 레이더 ===
export interface BreakoutRadarItem {
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number;
  dayHigh: number;
  peakAt: string;
  gapRate: number;
  tradingValue: number;
  themes: string[];
  themeCount: number;
}

export interface BreakoutRadarResponse {
  queriedAt: string;
  totalCount: number;
  stocks: BreakoutRadarItem[];
}

// === 시그널 전이 로그 ===
export type SignalEventType = "BREAKOUT" | "BREAKOUT_IMMINENT" | "VOLUME_SPIKE" | "MA20_CROSS";

export type SpikeDirection = "BUY" | "SELL" | "FLAT";

export interface SignalEventItem {
  occurredAt: string;
  stockCode: string;
  stockName: string;
  eventType: SignalEventType;
  currentPrice: number;
  priceChangeRate: number;
  tradingValue: number;
  gapRate: number | null;
  spikeRatio: number | null;
  minuteTradingValue: number | null;
  spikeDirection: SpikeDirection | null;
  ma20: number | null;
  theme: string | null;
}

export interface SignalEventsResponse {
  date: string;
  totalCount: number;
  events: SignalEventItem[];
}

// === 시장(코스피/코스닥) 투자자 순매수 시그널 ===
export type MarketType = "KOSPI" | "KOSDAQ";
export type InvestorType = "FOREIGN" | "INSTITUTION" | "INDIVIDUAL";
export type NetTradeSide = "BUY" | "SELL";
export type MarketSignalType = "NET_BUY_LEVEL" | "NET_FLOW_TURN" | "MA20_REBOUND" | "MA20_BREAKDOWN";

export interface MarketSignalEventItem {
  occurredAt: string;
  kind: MarketSignalType;
  market: MarketType;
  side: NetTradeSide;
  investor: InvestorType | null;
  level: number | null;
  thresholdEok: number | null;
  netAmountEok: number | null;
  extremeAmountEok: number | null;
  indexValue: number | null;
  changeRate: number | null;
}

export interface MarketSignalEventsResponse {
  date: string;
  totalCount: number;
  events: MarketSignalEventItem[];
}

/** 시장(코스피/코스닥) 장 마감(15:40) 투자자 순매수 스냅샷 — 단위 억원(부호 포함). */
export interface MarketCloseSnapshotItem {
  capturedAt: string;
  market: MarketType;
  foreignEok: number;
  institutionEok: number;
  individualEok: number;
  indexValue: number | null;
  changeRate: number | null;
}

/** 해외지수(나스닥종합 등) 장 마감 스냅샷 — 타임라인용. 지수값 + 등락률(부호 포함, %). */
export interface OverseasIndexCloseSnapshotItem {
  capturedAt: string;
  code: string;
  name: string;
  indexValue: number;
  changeRate: number;
}

/** 시장(코스피/코스닥) 당일 누적 투자자 순매수 — 단위 억원(부호 포함). */
export interface MarketInvestorNetBuyItem {
  market: MarketType;
  foreignEok: number;
  institutionEok: number;
  individualEok: number;
  indexValue: number;
  changeRate: number;
}

/** 지수 1분봉 — 가격은 지수값(소수), volume은 1000주 단위. */
export interface IndexMinuteCandleItem {
  time: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

// === 테마 캘린더 ===
export interface ThemeStockItem {
  stockCode: string;
  stockName: string;
  tradingValue: number;
  priceChangeRate: number | null;
}

export interface ThemeItem {
  rank: number;
  name: string;
  tradingValue: number;
  stocks: ThemeStockItem[];
}

export interface ThemeDayItem {
  date: string;
  themes: ThemeItem[];
}

export interface ThemeCalendarResponse {
  days: ThemeDayItem[];
}

export interface FilterResultItem {
  filterName: string;
  criteriaDescription: string;
  actualValue: string;
  passed: boolean;
}

export interface LeadingStockDetailResponse {
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number;
  relativeVolume: number | null;
  swingHighSignal: SwingHighSignal | null;
  themes: string[];
  filterResults: FilterResultItem[];
}

export interface DailyCandleItem {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface SwingHighSignal {
  peakPrice: number;
  peakAt: string;
  gapRate: number;
}

// === 해외주식 주도주 (Overseas Leading Stocks) ===

export interface OverseasFilterResult {
  filterName: string;
  criteriaDescription: string;
  actualValue: string;
  passed: boolean;
}

export interface OverseasSwingHighSignal {
  peakPrice: number;
  peakAt: string;
  gapRate: number; // (고점-현재가)/현재가×100, 양수=남은 상승률
}

export interface OverseasStockDetailResponse {
  exchange: string;
  symbol: string;
  name: string;
  ename: string;
  rank: number;
  price: number;
  diff: number;
  rate: number;
  tradingValue: number;
  marketCap: number | null; // 달러
  filterResults: OverseasFilterResult[];
  swingHighSignal: OverseasSwingHighSignal | null;
}

export interface OverseasSignalEventItem {
  occurredAt: string;
  exchange: string;
  symbol: string;
  name: string;
  eventType: SignalEventType; // BREAKOUT | BREAKOUT_IMMINENT | VOLUME_SPIKE | MA20_CROSS
  price: number;
  rate: number;
  tradingValue: number;
  gapRate: number | null;
  spikeRatio: number | null;
  minuteTradingValue: number | null;
  spikeDirection: SpikeDirection | null;
  ma20: number | null;
}

export interface OverseasSignalEventsResponse {
  date: string;
  totalCount: number;
  events: OverseasSignalEventItem[];
}

export interface OverseasStockRankItem {
  rank: number;
  exchange: string;
  symbol: string;
  name: string;
  ename: string;
  price: number;
  diff: number;
  rate: number;
  tradingValue: number;
}

/** 해외 돌파 현황 한 종목 — 가격 단위 USD. */
export interface OverseasBreakoutRadarItem {
  exchange: string;
  symbol: string;
  name: string;
  price: number;
  rate: number;
  tradingValue: number;
  dayHigh: number;
  peakAt: string;
  gapRate: number;
}

export interface InvestorTrendDay {
  date: string;
  individualNet: number;
  foreignNet: number;
  institutionNet: number;
  individualNetNxt: number;
  foreignNetNxt: number;
  institutionNetNxt: number;
}

/** 거래일에 사용자가 직접 남긴 이슈 메모 한 건. */
export interface DailyIssueItem {
  id: number;
  date: string; // YYYY-MM-DD
  content: string;
}
