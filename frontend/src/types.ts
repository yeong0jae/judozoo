export interface ApiResponse<T> {
  code: string;
  status: number;
  data: T | null;
}

export type MarketRegion = "KR" | "US";

export interface CalendarStatus {
  isHoliday: boolean;
  previousOpenDay: string | null;
}

export interface StockSearchResult {
  stockCode: string;
  stockName: string;
  exchange: string | null;
}

export interface MarketIndex {
  currentValue: number;
  changeRate: number;
}

export type KospiIndex = MarketIndex;

export interface OrgBreakdown {
  financialInvestmentEok: number;
  trustEok: number;
  pensionFundEok: number;
  privateEquityEok: number;
  insuranceEok: number;
  bankEok: number;
  otherFinanceEok: number;
}

export interface MarketInvestorDay {
  date: string;
  individualEok: number;
  foreignEok: number;
  institutionEok: number;
  otherCorpEok: number;
  breakdown: OrgBreakdown;
}

export interface StockOrgBreakdown {
  financialInvestmentMillion: number;
  trustMillion: number;
  pensionFundMillion: number;
  privateEquityMillion: number;
  insuranceMillion: number;
  bankMillion: number;
  otherFinanceMillion: number;
}

export interface StockInvestorDay {
  date: string;
  individualMillion: number;
  foreignMillion: number;
  institutionMillion: number;
  otherCorpMillion: number;
  breakdown: StockOrgBreakdown;
}

export interface MarketInvestorNets {
  individual: number;
  foreign: number;
  institution: number;
  otherCorp: number;
  breakdown: OrgBreakdown;
}

export interface MarketInvestorSession {
  name: string;
  time: string;
  nets: MarketInvestorNets | null;
  delta: MarketInvestorNets | null;
}

export interface FuturesQuote {
  futuresPrice: number;
  changeRate: number;
  spot: number;
  basis: number;
  dprt: number;
  openInterest: number;
  openInterestChange: number;
  rmnnDays: number;
  expiryDate: string;
}

export interface NightFuturesQuote {
  price: number;
  changeRate: number;
  dayClose: number;
  gap: number;
  open: number;
  high: number;
  low: number;
  volume: number;
  openInterest: number;
  openInterestChange: number;
}

export interface NasdaqIndexQuote {
  price: number;
  prevClose: number;
  priceChange: number;
  changeRate: number;
}

export interface NasdaqFuturesQuote {
  price: number;
  prevClose: number;
  priceChange: number;
  changeRate: number;
}

export interface MacroQuote {
  price: number;
  prevClose: number;
  priceChange: number;
  changeRate: number;
}

export interface MacroQuotes {
  usdKrw: MacroQuote | null;
  wti: MacroQuote | null;
  vix: MacroQuote | null;
  us10y: MacroQuote | null;
}

export type MacroTarget = "USD_KRW" | "WTI" | "VIX" | "US10Y";

export interface FuturesOrgBreakdown {
  securities: number;
  insurance: number;
  merchantBank: number;
  trust: number;
  privateEquity: number;
  fund: number;
  bank: number;
  otherOrg: number;
}

export interface FuturesNets {
  foreign: number;
  institution: number;
  individual: number;
  otherCorp: number;
  breakdown: FuturesOrgBreakdown;
}

export interface FuturesSession {
  name: string;
  time: string;
  nets: FuturesNets | null;
  delta: FuturesNets | null;
}

export interface FuturesInvestorDay {
  date: string;
  nets: FuturesNets;
}

export interface MarketCandleItem {
  date: string;
  time: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface CandidateStockItem {
  rank: number;
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number;
  accumulatedTradingValue: number;
}

export interface LimitUpItem {
  stockCode: string;
  stockName: string;
}

export interface LeadersResponse {
  leaders: CandidateStockItem[];
  limitUps: LimitUpItem[];
}

export interface CandidateStocksResponse {
  queriedAt: string;
  totalCount: number;
  stocks: CandidateStockItem[];
}

export interface MinuteCandleItem {
  time: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  tradingValue: number;
}

export interface BreakoutRadarItem {
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number;
  peakPrice: number;
  peakAt: string;
  gapRate: number;
  troughPrice: number | null;
  troughAt: string | null;
  supportGapRate: number | null;
  tradingValue: number;
}

export interface BreakoutRadarResponse {
  queriedAt: string;
  totalCount: number;
  stocks: BreakoutRadarItem[];
}

export type SignalEventType =
  | "VOLUME_SPIKE"
  | "MA_REBOUND"
  | "MA_BREAKDOWN"
  | "BREAKOUT"
  | "BREAKOUT_IMMINENT";

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
  ma: number | null;
}

export interface SignalEventsResponse {
  date: string;
  totalCount: number;
  events: SignalEventItem[];
}

export type MarketType = "KOSPI" | "KOSDAQ";
export type InvestorType = "FOREIGN" | "INSTITUTION" | "INDIVIDUAL";
export type NetTradeSide = "BUY" | "SELL";
export type MarketSignalType = "NET_BUY_LEVEL" | "NET_FLOW_TURN" | "MA_REBOUND" | "MA_BREAKDOWN";

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

export interface MarketInvestorNetBuyItem {
  market: MarketType;
  foreignEok: number;
  institutionEok: number;
  individualEok: number;
  otherCorpEok: number;
  financialInvestmentEok: number;
  trustEok: number;
  pensionFundEok: number;
  privateEquityEok: number;
  insuranceEok: number;
  bankEok: number;
  indexValue: number | null;
  changeRate: number | null;
}

export interface TodayNets {
  individual: number;
  foreign: number;
  institution: number;
  otherCorp: number;
}

export interface TodayNetItem {
  market: MarketType;
  futures: boolean;
  indexValue: number;
  changeRate: number;
  nets: TodayNets | null;
  tradeDate: string | null;
}

export interface IndexMinuteCandleItem {
  time: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface FilterResultItem {
  filterName: string;
  criteriaDescription: string;
  actualValue: string;
  passed: boolean;
  value: number | null;
  threshold: number | null;
}

export interface LeadingStockDetailResponse {
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number;
  relativeVolume: number | null;
  filterResults: FilterResultItem[];
  market: string | null;
  openingPrice: number;
  highPrice: number;
  lowPrice: number;
  previousClose: number;
  tradingValue: number | null;
}

export interface DailyCandleItem {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface OverseasFilterResult {
  filterName: string;
  criteriaDescription: string;
  actualValue: string;
  passed: boolean;
  value: number | null;
  threshold: number | null;
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
  marketCap: number | null;
  filterResults: OverseasFilterResult[];
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

export interface SessionsResponse<T> {
  date: string;
  sessions: T[];
}

export interface LeaderStockItem {
  rank: number;
  exchange: string | null;
  code: string;
  name: string;
  price: number;
  changeRate: number;
  tradingValue: number;
}

export interface LeaderDayItem {
  date: string;
  closed: boolean;
  live: boolean;
  stocks: LeaderStockItem[];
}

export interface LeaderCalendarResponse {
  domestic: LeaderDayItem[];
  overseas: LeaderDayItem[];
}

export interface LeaderTimelineStock {
  exchange: string | null;
  code: string;
  name: string;
}

export interface LeaderTimelineTick {
  at: string;
  stocks: number[];
  rates: number[];
  values: number[];
}

export interface LeaderTimelineResponse {
  stocks: LeaderTimelineStock[];
  ticks: LeaderTimelineTick[];
  lastTakenAt: string | null;
}

export interface InsightArticle {
  source: string;
  title: string;
  url: string;
}

export interface InsightReasonItem {
  code: string;
  explained: boolean;
  keywords: string[];
  reason: string;
  generatedAt: string;
  evidenceCount: number;
  relatedCount: number;
  evidence: InsightArticle[] | null;
  related: InsightArticle[] | null;
}
