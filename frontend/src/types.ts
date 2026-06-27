// Backend DTOs aligned 1:1 (Phase 5-A 기준).
// 참고: backend/src/main/kotlin/at/backend/{trading,system,account,stock}/...

// === Enums ===
export type TradingCycleStatus =
  | "INITIATED"
  | "BUYING"
  | "HOLDING"
  | "LIQUIDATING"
  | "CLOSED";

export type CloseReason =
  | "TAKE_PROFIT"
  | "STOP_LOSS"
  | "BREAKEVEN"
  | "TREND_BREAK"
  | "MARKET_CLOSE"
  | "CANCELLED"
  | "NO_FILL"
  | "UNCLOSED";

export type SignalType =
  | "STOP_LOSS"
  | "TP_2PCT"
  | "TP_3PCT"
  | "TP_5PCT"
  | "BREAKEVEN"
  | "TREND_BREAK"
  | "LIMIT_UP"
  | "MARKET_CLOSE"
  | "CANCEL";

export type ErrorCode =
  | "INVALID_PARAMETER"
  | "STOCK_NOT_FOUND"
  | "PRICE_BELOW_ONE_SHARE"
  | "INSUFFICIENT_BALANCE"
  | "DUPLICATE_COMMAND"
  | "HOLIDAY"
  | "OUT_OF_TRADING_HOURS"
  | "ALREADY_CLOSED"
  | "NOT_FOUND";

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

export interface AccountBalance {
  cashBalance: number;
  reservedAmount: number;
  availableBalance: number;
}

export interface Holding {
  stockCode: string;
  stockName: string;
  qty: number;
  avgBuyPrice: number;
  currentPrice: number;
  evalProfit: number;
  evalProfitRate: number; // 소수 (0.025 = 2.5%)
  hasActiveCycle: boolean;
}

export interface LiquidateHoldingResult {
  stockCode: string;
  qty: number;
  orderNo: string;
  krxFwdgOrdOrgno: string;
}

export interface TradingSummary {
  cycleId: number;
  stockCode: string;
  stockName: string;
  status: TradingCycleStatus;
  currentPrice: number;
  averageBuyPrice: number;
  profitRate: number; // 소수 (0.025 = 2.5%)
  profitAmount: number;
  holdingQty: number;
}

export interface TpStagesInfo {
  fired2pct: boolean;
  fired3pct: boolean;
  fired5pct: boolean;
}

export interface SplitSellProgressInfo {
  soldPct: number;
}

export interface OrderInfo {
  id: number;
  side: "BUY" | "SELL";
  trigger: string;
  status: string;
  orderQty: number;
  filledQty: number;
  submittedAt: string;
  settledAt: string | null;
  retryCount: number;
  lastError: string | null;
}

export interface ExecutionInfo {
  orderId: number;
  executedQty: number;
  executedPrice: number;
  fee: number;
  tax: number;
  executedAt: string;
}

export interface TradingDetail extends TradingSummary {
  totalBoughtQty: number;
  tpStages: TpStagesInfo;
  splitSellProgress: SplitSellProgressInfo;
  breakevenArmed: boolean;
  trendBreakArmed: boolean;
  closeReason: CloseReason | null;
  createdAt: string;
  closedAt: string | null;
  perBuyAmount: number;
  perBuyQty: number | null;
  splitSellRatio: number;
  breakevenThresholdPct: number;
  stopLossPct: number; // 백엔드는 양의 magnitude로 반환 (.negate())
  activeSell: unknown | null; // 백엔드 항상 null. Phase 6 이후 정의.
  orders: OrderInfo[];
  executions: ExecutionInfo[];
}

export interface DailyTrading {
  cycleId: number;
  stockCode: string;
  stockName: string;
  status: TradingCycleStatus;
  closeReason: CloseReason | null;
  profitRate: number | null;
  profitAmount: number | null;
  createdAt: string;
  closedAt: string | null;
}

// Phase 6: 수수료/세금/매수→매도가 분리 집계 (GET /api/reports/daily 응답)
export interface DailyReport {
  cycleId: number;
  stockCode: string;
  stockName: string;
  status: TradingCycleStatus;
  closeReason: CloseReason | null;
  createdAt: string;
  closedAt: string | null;
  avgBuyPrice: number | null;
  avgSellPrice: number | null;
  totalFee: number;
  totalTax: number;
  grossProfit: number | null;
  netProfit: number | null;
  profitRate: number | null;
}

export interface StockSearchResult {
  stockCode: string;
  stockName: string;
}

export interface StockPriceResult {
  stockCode: string;
  currentPrice: number;
  asOf: string;
}

// === REST requests / write responses ===
export interface CreateTradingRequest {
  stockCode: string;
  perBuyQty: number;
  splitSellRatio?: number | null;
  breakevenThresholdPct?: number | null;
  stopLossPct?: number | null;
}

export interface CreateTradingResult {
  id: number;
}

export interface CancelTradingResult {
  status: TradingCycleStatus;
  closeReason: CloseReason | null;
}

// === STOMP payloads (서버 → 클라이언트, spec §11.5) ===
export type TradingPayload =
  | {
      type: "PRICE";
      currentPrice: number;
      profitRate: number;
      profitAmount: number;
      ts: string;
    }
  | {
      type: "STATE";
      status: TradingCycleStatus;
      closeReason?: CloseReason | null;
      ts: string;
    }
  | {
      type: "SIGNAL";
      signalType: SignalType;
      event: "ARMED" | "FIRED";
      stage?: number;
      ts: string;
    }
  | {
      type: "EXECUTION";
      side: "BUY" | "SELL";
      qty: number;
      price: number;
      totalFilledQty: number;
      holdingQty: number;
      averageBuyPrice: number;
      ts: string;
    }
  | {
      type: "RETRY";
      signalType: SignalType;
      retryCount: number;
      lastError: string | null;
      ts: string;
    };

export type LifecyclePayload =
  | {
      type: "CREATED";
      cycleId: number;
      stockCode: string;
      stockName: string;
      ts: string;
    }
  | { type: "CLOSED"; cycleId: number; closeReason: CloseReason; ts: string };

export type MarketPayload =
  | { type: "HOLIDAY"; isHoliday: boolean; ts: string };

export type AccountPayload = { type: "BALANCE_INVALIDATED"; ts: string };

// === 시장 지수 ===

export interface MarketIndex {
  currentValue: number;
  changeRate: number; // 단위: % (양수=상승)
}

// 기존 호출부 호환
export type KospiIndex = MarketIndex;

// === 주도주 (Leading Stocks) ===

export interface CandidateStockItem {
  rank: number;
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number;
  accumulatedTradingValue: number;
  themes: string[]; // 대표 테마명 (상위 N개)
  themeCount: number; // 전체 테마 수 ("+N" 표기용)
}

export interface CandidateStocksResponse {
  queriedAt: string;
  totalCount: number;
  stocks: CandidateStockItem[];
}

// === 1분봉 캔들 (상세 차트) ===
export interface MinuteCandleItem {
  time: string; // ISO LocalDateTime (KST 벽시계)
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  tradingValue: number; // 거래대금(원, 종가×거래량 근사)
}

// === 돌파 임박 레이더 ===
export interface BreakoutRadarItem {
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number; // 당일 등락률(%)
  dayHigh: number; // 돌파선(당일 고가)
  peakAt: string; // 돌파선 형성 분봉 시각 (ISO LocalDateTime)
  gapRate: number; // 돌파까지 남은 상승률(%)
  tradingValue: number; // 당일 누적 거래대금(원)
  themes: string[];
  themeCount: number;
}

export interface BreakoutRadarResponse {
  queriedAt: string;
  totalCount: number;
  stocks: BreakoutRadarItem[];
}

// === 시그널 전이 로그 ===
export type SignalEventType = "BREAKOUT" | "BREAKOUT_IMMINENT" | "VOLUME_SPIKE";

export type SpikeDirection = "BUY" | "SELL" | "FLAT";

export interface SignalEventItem {
  occurredAt: string; // 전이 발생 시각 (ISO LocalDateTime)
  stockCode: string;
  stockName: string;
  eventType: SignalEventType;
  currentPrice: number;
  priceChangeRate: number; // 발생 시점 당일 등락률(%)
  tradingValue: number; // 발생 시점 당일 누적 거래대금(원)
  gapRate: number | null; // 돌파 계열만
  spikeRatio: number | null; // 스파이크만
  minuteTradingValue: number | null; // 스파이크만 — 발생 분봉 거래대금(원)
  spikeDirection: SpikeDirection | null; // 스파이크만 — 매수/매도
  theme: string | null;
}

export interface SignalEventsResponse {
  date: string; // yyyy-MM-dd
  totalCount: number;
  events: SignalEventItem[];
}

// === 시장(코스피/코스닥) 투자자 순매수 시그널 ===
export type MarketType = "KOSPI" | "KOSDAQ";
export type InvestorType = "FOREIGN" | "INSTITUTION" | "INDIVIDUAL";
export type NetTradeSide = "BUY" | "SELL";
export type MarketSignalType = "NET_BUY_LEVEL" | "CANDLE_STREAK";

export interface MarketSignalEventItem {
  occurredAt: string; // ISO LocalDateTime
  kind: MarketSignalType;
  market: MarketType;
  side: NetTradeSide; // 순매수 방향 / 양봉=BUY·음봉=SELL
  investor: InvestorType | null; // 순매수 시그널만
  level: number | null; // 순매수 — 도달 단계 (1=1단계)
  thresholdEok: number | null; // 순매수 — 단계 기준선(억원)
  netAmountEok: number | null; // 순매수 — 발생 시점 누적 순매수(억원, 부호 포함)
  streak: number | null; // 캔들 — 같은 색 연속 봉 수
  indexValue: number | null; // 발생 시점 지수값
  changeRate: number | null; // 발생 시점 등락률(%)
}

export interface MarketSignalEventsResponse {
  date: string; // yyyy-MM-dd
  totalCount: number;
  events: MarketSignalEventItem[];
}

/** 지수 1분봉 — 가격은 지수값(소수), volume은 1000주 단위. */
export interface IndexMinuteCandleItem {
  time: string; // ISO LocalDateTime
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
  priceChangeRate: number | null; // 캡처 시점 당일 등락률(%). 과거 적재분은 null.
}

export interface ThemeItem {
  rank: number;
  name: string;
  tradingValue: number; // 테마 소속 상위 종목 거래대금 합산(원)
  stocks: ThemeStockItem[]; // 거래대금 기여 종목(내림차순)
}

export interface ThemeDayItem {
  date: string; // YYYY-MM-DD
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
  relativeVolume: number | null; // 풀데이 RVOL — 당일 누적/직전 20일 평균 거래량. 없으면 null
  swingHighSignal: SwingHighSignal | null; // 직전 스윙 고점 돌파 시그널. 없으면 null
  themes: string[]; // 종목이 속한 전체 테마명
  filterResults: FilterResultItem[];
}

/** 일봉 한 개 — 일봉 차트용. date는 yyyy-MM-dd. */
export interface DailyCandleItem {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

/** 직전 스윙 고점 돌파 매매 시그널. gapRate 양수=남은 상승률(%), 음수=이미 돌파. */
export interface SwingHighSignal {
  peakPrice: number;
  peakAt: string; // 전고점이 형성된 분봉 시각 (ISO LocalDateTime)
  gapRate: number;
}

/** 단위: 백만원. 양수=순매수, 음수=순매도. NXT 컬럼은 NXT 거래소 단독. */
export interface InvestorTrendDay {
  date: string; // yyyy-MM-dd
  individualNet: number;
  foreignNet: number;
  institutionNet: number;
  individualNetNxt: number;
  foreignNetNxt: number;
  institutionNetNxt: number;
}

// 시그널 분석 — 매수/매도 스파이크를 분리한 종류
export type SignalKind =
  | "BREAKOUT"
  | "BREAKOUT_IMMINENT"
  | "SPIKE_BUY"
  | "SPIKE_SELL"
  | "SPIKE_FLAT";

// 종류별 사후 수익률 집계 (평균은 라벨된 건만 대상, 측정 불가는 null)
export interface SignalKindStat {
  kind: SignalKind;
  count: number;
  labeled: number;
  avg1m: number | null;
  avg2m: number | null;
  avg20m: number | null;
  avg2h: number | null;
  avgClose: number | null;
  avgMfe: number | null;
  avgMae: number | null;
  winRate20m: number | null; // +20분 > 0 비율(%)
}

// 신호 1건 + 사후 라벨 (라벨 없으면 수익률 null)
export interface SignalRow {
  occurredAt: string;
  kind: SignalKind;
  currentPrice: number;
  priceChangeRate: number;
  gapRate: number | null;
  spikeRatio: number | null;
  ret1m: number | null;
  ret2m: number | null;
  ret20m: number | null;
  ret2h: number | null;
  retClose: number | null;
  mfe: number | null; // 신호 후 당일 최대 상승
  mae: number | null; // 신호 후 당일 최대 하락
}

// 한 종목의 그날 여정 (신호 시간순 + 헤더 요약)
export interface StockSignalGroup {
  stockCode: string;
  stockName: string;
  theme: string | null;
  bestMfe: number | null;
  closeRet: number | null;
  signals: SignalRow[];
}

export interface SignalAnalysis {
  date: string;
  dayStats: SignalKindStat[];
  overallStats: SignalKindStat[];
  stocks: StockSignalGroup[];
}
