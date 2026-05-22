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
  | "MIDWAY_TP"
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
  | "CUTOFF_PASSED"
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
  cutoffPassed: boolean;
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

export interface BuyAttemptInfo {
  completed: number;
  total: number; // 항상 3
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
  buyAttempt: BuyAttemptInfo;
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
  buyIntervalMin: number;
  splitSellRatio: number;
  midwayProfitPct: number;
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
  buyIntervalMin?: number | null;
  splitSellRatio?: number | null;
  midwayProfitPct?: number | null;
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

// === 주도주 (Leading Stocks) ===

export interface CandidateStockItem {
  rank: number;
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number;
  accumulatedTradingValue: number;
}

export interface CandidateStocksResponse {
  queriedAt: string;
  totalCount: number;
  stocks: CandidateStockItem[];
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
  filterResults: FilterResultItem[];
}
