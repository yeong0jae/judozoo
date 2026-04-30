export type CommandStatus =
  | "INITIATED"
  | "BUYING"
  | "MONITORING"
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

export interface CommandSummary {
  commandId: string;
  stockCode: string;
  stockName: string;
  status: CommandStatus;
  currentPrice: number;
  averageBuyPrice: number;
  profitRate: number; // %
  profitAmount: number; // 원
  holdingQty: number;
  buyAttempt: { completed: number; total: number };
}

export interface CommandDetail extends CommandSummary {
  totalBoughtQty: number;
  tpStages: { fired2pct: boolean; fired3pct: boolean; fired5pct: boolean };
  splitSellProgress: { soldPct: number };
  breakevenArmed: boolean;
  trendBreakArmed: boolean;
  closeReason: CloseReason | null;
  createdAt: string;
  closedAt: string | null;
  perBuyAmount: number;
  buyIntervalMin: number;
  splitSellRatio: number;
  midwayProfitPct: number;
  breakevenThresholdPct: number;
  stopLossPct: number;
  activeSell: {
    signalType: SignalType;
    retryCount: number;
    lastError: string | null;
  } | null;
}

export interface DailyReportRow {
  commandId: string;
  date: string; // YYYY-MM-DD
  stockCode: string;
  stockName: string;
  boughtAmount: number;
  soldAmount: number;
  fee: number;
  tax: number;
  netProfit: number;
  profitRate: number;
  closeReason: CloseReason;
}

export type MarketMode = "WS" | "POLLING";
export type TokenStatus = "OK" | "REFRESH_FAILED";

export interface SystemStatus {
  marketMode: MarketMode;
  tokenStatus: TokenStatus;
  isHoliday: boolean;
  tradingHoursOpen: boolean;
  cutoffPassed: boolean;
  unclosedCount: number;
}

export interface AccountBalance {
  cashBalance: number;
  reservedAmount: number;
  availableBalance: number;
}
