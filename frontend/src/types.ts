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
  exchange: string | null; // NAS/NYS/AMS. null이면 국내
}

// === 시장 지수 ===

export interface MarketIndex {
  currentValue: number;
  changeRate: number; // 단위: % (양수=상승)
}

export type KospiIndex = MarketIndex;

// === 시장 투자자 순매수 (키움 ka10051, 시황분석 코스피/코스닥 상세용) ===

/** 기관 세부 순매수(억원) — 키움 7종. */
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
  date: string; // yyyy-MM-dd
  individualEok: number;
  foreignEok: number;
  institutionEok: number;
  otherCorpEok: number;
  breakdown: OrgBreakdown;
}

/** 종목 일별 순매수(억원) — 키움 ka10059. 시장 수급과 같은 구성이라 표를 공유한다. */
export type StockInvestorDay = MarketInvestorDay;

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
}

/** 프로그램 순매수(억원) — 차익·비차익·전체. */
export interface ProgramNets {
  arbitrageEok: number;
  nonArbitrageEok: number;
  totalEok: number;
}

/** 프로그램 세션별(오전/오후/막판) 순매수. 없는 세션은 nets=null. */
export interface ProgramSession {
  name: string;
  time: string;
  nets: ProgramNets | null;
}

/** 하루치 프로그램 순매수(억원). */
export interface ProgramDay {
  date: string; // yyyy-MM-dd
  arbitrageEok: number;
  nonArbitrageEok: number;
  totalEok: number;
}

/** 코스피 선물(근월물) 시세 요약 — KIS 국내선물옵션. 값은 지수 포인트. */
export interface FuturesQuote {
  futuresPrice: number;
  changeRate: number; // 선물 등락률(%)
  spot: number; // 현물 KOSPI200
  basis: number; // 선물 − 현물
  dprt: number; // 괴리율(%) — 선물이 이론가 대비 얼마나 고평가인가
  openInterest: number; // 미결제약정(계약)
  openInterestChange: number; // 전일 대비 증감
  rmnnDays: number; // 만기 잔존일수
  expiryDate: string; // 만기일 yyyy-MM-dd
  investors: FuturesInvestors | null; // 투자자별 순매수(계약). 조회 실패 시 null
}

/** 선물 시장 투자자별 순매수(계약). 양수 = 순매수. */
export interface FuturesInvestors {
  foreign: number;
  individual: number;
  institution: number;
}

/** 코스피 야간선물(18:00~익일 06:00) 시세 — KIS 시장구분 CM. 값은 지수 포인트. */
export interface NightFuturesQuote {
  price: number;
  changeRate: number; // 직전 정규장 종가 대비 등락률(%)
  dayClose: number; // 직전 정규장 종가
  gap: number; // 정규장 종가 대비 갭(포인트)
  open: number;
  high: number;
  low: number;
  volume: number;
  openInterest: number;
  openInterestChange: number;
}

/** 나스닥100 선물(CME NQ) 시세 — 야후 파이낸스. 값은 지수 포인트(USD). */
export interface NasdaqFuturesQuote {
  price: number;
  prevClose: number; // 전일 종가
  priceChange: number; // 전일 대비(포인트)
  changeRate: number; // 전일 대비 등락률(%)
}

/** 나스닥 종합지수(^IXIC) — 야후 파이낸스. */
export interface NasdaqIndexQuote {
  price: number;
  prevClose: number; // 전일 종가
  priceChange: number; // 전일 대비(포인트)
  changeRate: number; // 전일 대비 등락률(%)
}

/** 매크로 지표(원달러·WTI) 시세 — 야후 파이낸스. */
export interface MacroQuote {
  price: number;
  prevClose: number; // 전일 종가
  priceChange: number; // 전일 대비
  changeRate: number; // 전일 대비 등락률(%)
}

/** 원달러·WTI·VIX 묶음 — 일부만 실패하면 그쪽만 null. */
export interface MacroQuotes {
  usdKrw: MacroQuote | null;
  wti: MacroQuote | null;
  vix: MacroQuote | null;
}

/** 매크로 상세가 다루는 대상 — 백엔드 enum과 이름이 같아야 한다. */
export type MacroTarget = "USD_KRW" | "WTI" | "VIX";

/** 선물 기관 세부 순매수(계약) — KIS 선물 분류. */
export interface FuturesOrgBreakdown {
  securities: number; // 증권
  insurance: number; // 보험
  merchantBank: number; // 종금
  trust: number; // 투신
  privateEquity: number; // 사모펀드
  fund: number; // 기금
  bank: number; // 은행
  otherOrg: number; // 기타단체
}

/** 선물 세션 순매수(계약). */
export interface FuturesNets {
  foreign: number;
  institution: number;
  individual: number;
  otherCorp: number;
  breakdown: FuturesOrgBreakdown;
}

/** 선물 세션별(오전/오후/막판) 순매수(계약). 스냅샷이 없는 세션은 nets=null. */
export interface FuturesSession {
  name: string;
  time: string;
  nets: FuturesNets | null;
}

/** 종목 관련 뉴스·공시 한 건 — KIS 종합 시황/공시. 제목만 오고 원문 링크는 없다. */
export interface StockNewsItem {
  seqNo: string;
  title: string;
  source: string; // 언론사명 또는 "공시"
  disclosure: boolean;
  publishedAt: string; // ISO LocalDateTime (KST)
}

/** 선물 일별 순매수(계약) — 그날의 당일 누적. 스냅샷을 쌓은 날만 온다. */
export interface FuturesInvestorDay {
  date: string; // yyyy-MM-dd
  nets: FuturesNets;
}

/** 시장 지수 캔들(OHLCV) — 토스 Market Indicators. date는 KST, time은 1d일 땐 00:00:00 고정. */
export interface MarketCandleItem {
  date: string;
  time: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

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
  otherCorpEok: number;
  // 기관 세부 (억원). 시그널 시점 조회는 컬럼 추가 이후 값부터 채워짐.
  financialInvestmentEok: number;
  trustEok: number;
  pensionFundEok: number;
  privateEquityEok: number;
  insuranceEok: number;
  bankEok: number;
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

// === 관심 테마 (사용자 큐레이션) ===

export interface WatchStock {
  stockCode: string;
  stockName: string;
  exchange: string | null; // NAS/NYS/AMS. null이면 국내
}

export interface WatchTheme {
  id: number;
  name: string;
  stocks: WatchStock[];
}

/** 관심 종목 시세 — 국내는 키움, 해외는 야후. */
export interface StockQuote {
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number;
  overseas: boolean;
}
