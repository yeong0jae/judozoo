// === REST envelope ===
export interface ApiResponse<T> {
  code: string;
  status: number;
  data: T | null;
}

// === REST responses ===
/** 배너용 시장 휴장 상태. region은 KR(국내)/US(해외). */
export type MarketRegion = "KR" | "US";

export interface CalendarStatus {
  isHoliday: boolean;
  /** 오늘 전의 마지막 개장일(yyyy-MM-dd) — 국내만. 서버가 모르면 null */
  previousOpenDay: string | null;
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

/** 종목 기관 세부 순매수(백만원) — 키움 7종. 종목은 억 반올림 시 작은 값이 뭉개져 백만원으로 둔다. */
export interface StockOrgBreakdown {
  financialInvestmentMillion: number;
  trustMillion: number;
  pensionFundMillion: number;
  privateEquityMillion: number;
  insuranceMillion: number;
  bankMillion: number;
  otherFinanceMillion: number;
}

/** 종목 일별 순매수(백만원) — 키움 ka10059. */
export interface StockInvestorDay {
  date: string; // yyyy-MM-dd
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
  /** 직전 스냅샷 대비 변화량. 마지막 스냅샷이 속한 구간에만, 그리고 오늘만 온다. */
  delta: MarketInvestorNets | null;
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

/** 나스닥 종합지수(^IXIC) — 야후 파이낸스. */
export interface NasdaqIndexQuote {
  price: number;
  prevClose: number; // 전일 종가
  priceChange: number; // 전일 대비(포인트)
  changeRate: number; // 전일 대비 등락률(%)
}

/** 나스닥100 선물(NQ=F) 시세 — 야후. 무료 시세라 10분쯤 지연된다. */
export interface NasdaqFuturesQuote {
  price: number;
  prevClose: number; // 전일 종가
  priceChange: number; // 전일 대비(포인트)
  changeRate: number; // 전일 대비 등락률(%)
}

/** 매크로 지표(원달러·WTI·VIX·미국채 10년) 시세 — 야후 파이낸스. */
export interface MacroQuote {
  price: number;
  prevClose: number; // 전일 종가
  priceChange: number; // 전일 대비
  changeRate: number; // 전일 대비 등락률(%)
}

/** 원달러·WTI·VIX·미국채 10년 묶음 — 일부만 실패하면 그쪽만 null. */
export interface MacroQuotes {
  usdKrw: MacroQuote | null;
  wti: MacroQuote | null;
  vix: MacroQuote | null;
  us10y: MacroQuote | null; // 금리 %가 그대로 온다 (5.21 = 5.21%)
}

/** 매크로 상세가 다루는 대상 — 백엔드 enum과 이름이 같아야 한다. */
export type MacroTarget = "USD_KRW" | "WTI" | "VIX" | "US10Y";

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

/** 선물 세션별(오전/오후/마감) 순매수(계약). 스냅샷이 없는 세션은 nets=null. */
export interface FuturesSession {
  name: string;
  time: string;
  nets: FuturesNets | null;
  /** 직전 스냅샷 대비 변화량. `MarketInvestorSession.delta`와 같은 규칙이다. */
  delta: FuturesNets | null;
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
}

/** 첫 화면 상한가 칩 — 이름만 쓰는 자리라 시세가 없다. */
export interface LimitUpItem {
  stockCode: string;
  stockName: string;
}

/** 첫 화면 주도주 카드가 통째로 쓰는 응답 — 탑5와 상한가가 같은 후보 풀에서 나온다. */
export interface LeadersResponse {
  leaders: CandidateStockItem[];
  limitUps: LimitUpItem[];
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
  peakPrice: number;
  peakAt: string;
  gapRate: number;
  /** 지지선 — 분봉이 없으면 null. 저항선은 있는데 지지선만 없는 경우는 없다. */
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

// === 시그널 전이 로그 ===
export type SignalEventType =
  | "VOLUME_SPIKE"
  // 돌파·임박은 2026-09-13, 반등·꺾임은 2026-09-28에 생성을 중단했다. 과거 날짜를 보면
  // DB에 남은 행이 조회되므로 표시 경로는 계속 살려 둔다.
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

// === 시장(코스피/코스닥) 투자자 순매수 시그널 ===
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
  // 스냅샷 적재 이전이거나 지수 수집이 실패한 시점이면 null이다 (백엔드 float | None).
  indexValue: number | null;
  changeRate: number | null;
}

/** 투자자 넷. 단위는 `TodayNetItem.futures`가 가른다 — 현물은 억원, 선물은 계약. */
export interface TodayNets {
  individual: number;
  foreign: number;
  institution: number;
  otherCorp: number;
}

/**
 * 첫 화면 "오늘의 수급" 한 칸.
 *
 * `nets`는 수급만 못 받았을 때 null이다 — 시세는 왔으므로 칸은 남고 수급 줄만 빈다.
 */
export interface TodayNetItem {
  market: MarketType;
  futures: boolean;
  indexValue: number;
  changeRate: number;
  nets: TodayNets | null;
  /** 수급의 날짜(YYYY-MM-DD). 장 열기 전·휴장일엔 직전 거래일이다. */
  tradeDate: string | null;
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

export interface FilterResultItem {
  filterName: string;
  criteriaDescription: string;
  actualValue: string;
  passed: boolean;
  value: number | null; // 눈금 막대용 실측값. 막대가 없는 조건이나 값이 없으면 null
  threshold: number | null;
}

export interface LeadingStockDetailResponse {
  stockCode: string;
  stockName: string;
  currentPrice: number;
  priceChangeRate: number;
  relativeVolume: number | null;
  filterResults: FilterResultItem[];
  market: string | null; // "KOSPI" | "KOSDAQ". 카탈로그에 없으면 null
  openingPrice: number;
  highPrice: number;
  lowPrice: number;
  previousClose: number;
  tradingValue: number | null; // 원. 거래대금 순위 밖이면 null
}

export interface DailyCandleItem {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

// === 해외주식 주도주 (Overseas Leading Stocks) ===

export interface OverseasFilterResult {
  filterName: string;
  criteriaDescription: string;
  actualValue: string;
  passed: boolean;
  value: number | null; // 눈금 막대용 실측값. 막대가 없는 조건이나 값이 없으면 null
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
  marketCap: number | null; // 달러
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

/** 시간대별 수급 응답 — `date`는 **실제로 조회된 날짜**다.
 *  공휴일이면 백엔드가 직전 거래일로 물러나므로, 요청한 날짜로 라벨을 붙이면 안 된다. */
export interface SessionsResponse<T> {
  date: string;
  sessions: T[];
}

/** 주도주 캘린더 — 홈 주도주 카드가 마감 때 고른 종목 한 줄.
 *  가격·거래대금 단위는 시장으로 읽는다(국내 원, 해외 달러). `exchange`는 해외만. */
export interface LeaderStockItem {
  rank: number;
  exchange: string | null;
  code: string;
  name: string;
  price: number;
  changeRate: number; // %
  tradingValue: number;
}

/** `closed`면 휴장. 아니고 `stocks`가 비면 그날 주도주가 없었다. 날 자체가 없으면 기록이 없다.
 *  `live`면 마감 기록 전의 오늘이라 순위가 아직 바뀐다. `date`는 현지 거래일. */
export interface LeaderDayItem {
  date: string;
  closed: boolean;
  live: boolean;
  stocks: LeaderStockItem[];
}

export interface LeaderCalendarResponse {
  domestic: LeaderDayItem[];
  /** 요청한 달 1일의 직전 평일부터 — 1일 칸에 붙는 해외장이 전달에 있다. */
  overseas: LeaderDayItem[];
}

/** 주도주 타임라인 — 1분마다 찍은 홈 주도주. 종목은 사전으로 한 번만 오고 분마다 번호로 온다. */
export interface LeaderTimelineStock {
  exchange: string | null;
  code: string;
  name: string;
}

/** 한 분. `stocks`는 사전 번호(순위 순) — 비면 그 분엔 주도주가 없었다. `at`은 현지 HH:MM(해외는 뉴욕). */
export interface LeaderTimelineTick {
  at: string;
  stocks: number[];
  rates: number[]; // %
  values: number[]; // 국내 원, 해외 달러
}

export interface LeaderTimelineResponse {
  stocks: LeaderTimelineStock[];
  ticks: LeaderTimelineTick[];
  /** 마지막으로 찍은 실제 시각(KST, 시간대 없는 ISO) */
  lastTakenAt: string | null;
}

// ============================================================
// 왜 오르나 (026)
// ============================================================

export interface InsightArticle {
  source: string;
  title: string;
  url: string;
}

/** 종목 하나의 "왜 오르나". 설명 없음이면 `explained`가 false이고 사유·키워드·근거가 비어 있다. */
export interface InsightReasonItem {
  /** 국내 6자리 단축코드 / 해외 심볼 */
  code: string;
  explained: boolean;
  keywords: string[];
  reason: string;
  /** KST, 시간대 없는 ISO — 화면의 "기준" 시각 */
  generatedAt: string;
  evidenceCount: number;
  relatedCount: number;
  /** 로그인일 때만 온다 */
  evidence: InsightArticle[] | null;
  related: InsightArticle[] | null;
}
