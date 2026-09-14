// 종가 베팅 화면 — 프론트 전용 목 데이터. 백엔드 연동 전 전시 구성용.
// 값은 시드 기반으로 결정론적 생성해 같은 종목/테마는 늘 같은 숫자를 보인다.
import type { UTCTimestamp } from "lightweight-charts";
import type { CandleSeries } from "../common/CandleChart";

const UP = "rgba(240,68,82,0.5)"; // 상승 빨강 (CandleChart와 동일 톤)
const DOWN = "rgba(59,130,246,0.5)"; // 하락 파랑

export interface CbStock {
  name: string;
  code: string;
  price: number;
  /** 전일 대비 등락률(%) */
  pct: number;
}
export interface CbTheme {
  name: string;
  /** 테마 한 줄 재료/이슈 */
  news: string;
  stocks: CbStock[];
  /** 관련 지표·ETF 이름 (DRAM 현물가, SOXX 등) */
  related: string[];
}
export interface CbIndex {
  id: string;
  name: string;
  value: number;
  pct: number;
}

// ── 시드 RNG ─────────────────────────────────────────────
function seeded(str: string): () => number {
  let h = 1779033703 ^ str.length;
  for (let i = 0; i < str.length; i++) {
    h = Math.imul(h ^ str.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  return () => {
    h = Math.imul(h ^ (h >>> 16), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    h ^= h >>> 16;
    return (h >>> 0) / 4294967296;
  };
}

// [name, code, price, pct]
type Row = [string, string, number, number];
const T = (name: string, news: string, related: string[], rows: Row[]): CbTheme => ({
  name,
  news,
  related,
  stocks: rows.map(([n, c, p, r]) => ({ name: n, code: c, price: p, pct: r })),
});

export const THEMES: CbTheme[] = [
  T("반도체", "엔비디아 협력 확대로 HBM 수요 강세", ["DRAM 현물가", "SOXX 미반도체ETF", "필라델피아 반도체지수", "KODEX 반도체"], [
    ["SK하이닉스", "000660", 201000, 3.05], ["삼성전자", "005930", 86500, 1.42], ["SK스퀘어", "402340", 141800, 6.85], ["DB하이텍", "000990", 52300, -1.2],
  ]),
  T("반도체 장비", "전공정 투자 재개 기대", ["필라델피아 반도체지수", "마이크론(MU)", "TIGER Fn반도체TOP10"], [
    ["한미반도체", "042700", 222000, 3.01], ["주성엔지니어링", "036930", 191900, 7.56], ["이수페타시스", "007660", 103200, 12.41], ["제주반도체", "080220", 89100, 14.67], ["원익IPS", "240810", 31450, 2.1],
  ]),
  T("MLCC", "AI서버향 고용량 MLCC 단가 인상", ["무라타(일본)", "TDK(일본)", "MLCC 수출물가"], [
    ["삼성전기", "009150", 158000, 5.82], ["LG이노텍", "011070", 274100, 0.0], ["삼화콘덴서", "001820", 110200, 9.0], ["SKC", "011790", 95000, 3.37],
  ]),
  T("전력", "정부 전력망 투자 발표(연속성 관찰)", ["미 유틸리티 XLU", "구리 선물", "전력망 수주잔고"], [
    ["LS ELECTRIC", "010120", 218500, 4.1], ["HD현대일렉트릭", "267260", 412000, 2.8], ["효성중공업", "298040", 498000, -0.9], ["제룡전기", "033100", 63200, 6.2],
  ]),
  T("광통신", "북미 데이터센터 광모듈 수주", ["코히런트(COHR)", "엔비디아 DC매출", "광모듈 BOM단가"], [
    ["오이솔루션", "138080", 41250, 8.3], ["라이트론", "069540", 6820, 3.1], ["대한광통신", "010170", 3985, -1.4],
  ]),
  T("피지컬 AI", "휴머노이드 상용화 로드맵 공개", ["테슬라 옵티머스", "엔비디아 로보틱스", "ROBO ETF"], [
    ["레인보우로보틱스", "277810", 218000, 5.4], ["두산로보틱스", "454910", 82300, 3.9], ["로보스타", "090360", 24150, -2.1],
  ]),
  T("AI 에이전트", "국산 LLM 에이전트 도입 확산", ["MS 코파일럿", "오픈AI 동향", "팔란티어(PLTR)"], [
    ["솔트룩스", "304100", 18240, 7.2], ["코난테크놀로지", "402030", 42100, 4.5], ["폴라리스오피스", "041020", 14320, 1.8],
  ]),
  T("소프트웨어", "B2B SaaS 구독매출 성장", ["IGV 소프트웨어ETF", "서비스나우(NOW)"], [
    ["더존비즈온", "012510", 68200, 2.2], ["한글과컴퓨터", "030520", 22150, -0.7], ["알체라", "347860", 11480, 3.3],
  ]),
  T("클라우드/데이터센터", "하이퍼스케일 증설 발표", ["엔비디아(NVDA)", "데이터센터 REIT", "KRX 인터넷"], [
    ["NAVER", "035420", 216500, 1.9], ["카카오", "035720", 42350, 0.9], ["가비아", "079940", 12980, 4.1],
  ]),
  T("배터리", "유럽 전기차 보조금 재개", ["리튬 선물", "LIT 배터리ETF", "유럽 EV 판매"], [
    ["LG에너지솔루션", "373220", 382000, 2.7], ["삼성SDI", "006400", 298500, -1.1], ["에코프로비엠", "247540", 132400, 3.6], ["포스코퓨처엠", "003670", 201000, 1.2],
  ]),
  T("바이오", "ADC 기술이전 계약 기대", ["XBI 바이오ETF", "원/달러 환율", "나스닥 바이오"], [
    ["삼성바이오로직스", "207940", 1042000, 1.3], ["셀트리온", "068270", 182300, 0.6], ["알테오젠", "196170", 328000, 4.8], ["유한양행", "000100", 148900, -0.8],
  ]),
  T("방산", "중동·유럽 수출 협상 진전", ["ITA 방산ETF", "유가(WTI)", "수출 수주잔고"], [
    ["한화에어로스페이스", "012450", 892000, 3.2], ["한국항공우주", "047810", 68200, 1.7], ["LIG넥스원", "079550", 312500, 2.4], ["현대로템", "064350", 158700, 5.1],
  ]),
  T("금융", "밸류업 지수 편입 기대", ["XLF 금융ETF", "국고채 10년", "원/달러 환율"], [
    ["KB금융", "105560", 98600, 1.1], ["신한지주", "055550", 62400, 0.8], ["메리츠금융지주", "138040", 132000, -0.4],
  ]),
  T("양자", "양자암호통신 국책과제 선정", ["아이온큐(IONQ)", "리게티(RGTI)", "디웨이브(QBTS)"], [
    ["우리넷", "115440", 9840, 6.9], ["케이씨에스", "115500", 12180, 11.2], ["엑스게이트", "356680", 5230, 3.4],
  ]),
  T("원전", "체코 원전 후속 계약 협상", ["URA 우라늄ETF", "카메코(CCJ)", "SMR 관련주"], [
    ["두산에너빌리티", "034020", 28150, 4.6], ["한전기술", "052690", 82400, 2.1], ["우진", "105840", 7280, -1.9], ["비에이치아이", "083650", 18620, 7.7],
  ]),
  T("건설", "금리 인하 기대 · 정비사업 재개", ["ITB 건설ETF", "국고채 3년", "미분양 지수"], [
    ["현대건설", "000720", 38200, 2.3], ["GS건설", "006360", 18740, 1.4], ["대우건설", "047040", 4185, -0.6],
  ]),
  T("조선", "LNG선 수주 모멘텀 유지(연속성 ○)", ["클락슨 신조선가", "LNG선 운임", "후판 가격"], [
    ["HD한국조선해양", "009540", 312000, 3.8], ["삼성중공업", "010140", 14820, 2.9], ["한화오션", "042660", 68400, 4.2], ["HD현대중공업", "329180", 382500, 1.6],
  ]),
];

export const INDICES: CbIndex[] = [
  { id: "kospi", name: "코스피", value: 2984.21, pct: 0.82 },
  { id: "kospiF", name: "코스피 선물", value: 398.15, pct: 0.94 },
  { id: "nightF", name: "야간 선물", value: 398.8, pct: 0.16 },
  { id: "nasF", name: "나스닥 선물", value: 23120.5, pct: -0.34 },
  { id: "nasdaq", name: "나스닥", value: 26281.61, pct: 0.29 },
];

export const stockByName: Record<string, { theme: string; stock: CbStock }> = {};
export const themeByName: Record<string, CbTheme> = {};
for (const t of THEMES) {
  themeByName[t.name] = t;
  for (const s of t.stocks) stockByName[s.name] = { theme: t.name, stock: s };
}

// ── 파생 목 데이터 ────────────────────────────────────────
const DAYS = ["07.11", "07.10", "07.09", "07.08", "07.07", "07.04", "07.03", "07.02", "07.01", "06.30"];

export interface InvestorDay {
  date: string;
  foreign: number; // 억원
  inst: number;
  indiv: number;
}
/** 최근 10일 투자자별 순매수(억원). 외인+기관의 반대를 개인으로 대략 맞춘다. */
export function investorDays(key: string): InvestorDay[] {
  const rng = seeded(key + "inv");
  return DAYS.map((date) => {
    const foreign = Math.round((rng() - 0.45) * 900);
    const inst = Math.round((rng() - 0.5) * 700);
    const indiv = -(foreign + inst) + Math.round((rng() - 0.5) * 120);
    return { date, foreign, inst, indiv };
  });
}

export interface OrgPart {
  label: string;
  value: number; // 억원 (오늘)
}
/** 기관 세부(연기금·투신·금융투자·사모·보험·은행) 오늘 순매수. */
export function orgBreakdown(key: string): OrgPart[] {
  const rng = seeded(key + "org");
  return ["연기금", "투신", "금융투자", "사모", "보험", "은행"].map((label) => ({
    label,
    value: Math.round((rng() - 0.5) * 400),
  }));
}

export interface TradingDay {
  date: string;
  eok: number; // 거래대금(억원)
}
export function tradingValues(key: string): TradingDay[] {
  const rng = seeded(key + "val");
  return DAYS.slice().reverse().map((date) => ({ date, eok: Math.round((0.4 + rng()) * 8000) }));
}

export interface Session {
  name: string;
  time: string;
  tag?: string;
  foreign: number;
  inst: number;
  indiv: number;
}
export function sessions(key: string): Session[] {
  const rng = seeded(key + "ses");
  const meta: [string, string, string?][] = [
    ["오전", "09:00~12:00"],
    ["오후", "12:00~14:40"],
    ["마감 구간", "14:40~15:30", "종가 결정"],
  ];
  return meta.map(([name, time, tag]) => {
    const foreign = Math.round((rng() - 0.45) * 500);
    const inst = Math.round((rng() - 0.5) * 400);
    return { name, time, tag, foreign, inst, indiv: -(foreign + inst) };
  });
}

export interface RelIndicator {
  name: string;
  value: number;
  pct: number;
}
export function relIndicator(name: string): RelIndicator {
  const rng = seeded(name + "rel");
  return { name, value: Math.round((50 + rng() * 1450) * 100) / 100, pct: +((rng() - 0.45) * 8).toFixed(2) };
}

/** 신고가(52주)까지 남은 거리(%) — 음수(고점 아래). */
export function highDistance(key: string): number {
  const rng = seeded(key + "hi");
  return -(2 + rng() * 22);
}

/** NXT 오늘 순매수(억) — 외인/기관/개인. */
export function nxtFlow(key: string): { foreign: number; inst: number; indiv: number } {
  const rng = seeded(key + "nxt");
  const foreign = Math.round((rng() - 0.4) * 300);
  const inst = Math.round((rng() - 0.55) * 200);
  return { foreign, inst, indiv: -(foreign + inst) };
}

// ── 차트 시리즈 (CandleChart 재사용) ──────────────────────
export function mockSeries(key: string, minute: boolean): CandleSeries {
  const rng = seeded(key + (minute ? "min" : "day"));
  const n = 40;
  let price = 100 + rng() * 40;
  const start = minute ? Date.UTC(2026, 6, 11, 0, 0, 0) / 1000 : Date.UTC(2026, 5, 1, 0, 0, 0) / 1000;
  const step = minute ? 300 : 86400;
  const candles: CandleSeries["candles"] = [];
  const volumes: CandleSeries["volumes"] = [];
  for (let i = 0; i < n; i++) {
    const open = price;
    price = Math.max(50, price + (rng() - 0.48) * 4);
    const close = price;
    const high = Math.max(open, close) + rng() * 1.5;
    const low = Math.min(open, close) - rng() * 1.5;
    const time = (start + i * step) as UTCTimestamp;
    candles.push({ time, open, high, low, close });
    volumes.push({ time, value: Math.round((0.4 + rng()) * 1e9), color: close >= open ? UP : DOWN });
  }
  return { candles, volumes };
}

// ── 뉴스 ─────────────────────────────────────────────────
export interface NewsItem {
  src: string;
  headline: string;
  time: string;
  hot?: boolean;
}
const STOCK_NEWS: Record<string, NewsItem[]> = {
  SK하이닉스: [
    { src: "한국경제", headline: "HBM4 조기 양산…엔비디아 공급 확대", time: "12분 전", hot: true },
    { src: "연합뉴스", headline: "SK하이닉스, 3분기 영업익 컨센 상회 전망", time: "1시간 전" },
    { src: "이데일리", headline: "외국인 8일 연속 순매수 지속", time: "2시간 전" },
  ],
};
const DEFAULT_NEWS: NewsItem[] = [
  { src: "한국경제", headline: "업종 대표주 수급 개선…기관 매수 유입", time: "30분 전" },
  { src: "연합뉴스", headline: '증권가 "목표주가 상향" 리포트 발간', time: "1시간 전" },
  { src: "머니투데이", headline: "테마 모멘텀 지속 여부 주목", time: "3시간 전" },
];
export const MARKET_NEWS: NewsItem[] = [
  { src: "글로벌", headline: "[속보] 엔비디아·삼성 HBM 협력 공식화", time: "8분 전", hot: true },
  { src: "정부정책", headline: "정부, 전력망 10년 투자계획 발표", time: "40분 전" },
  { src: "테마", headline: "조선 LNG선 신규 수주 릴레이", time: "1시간 전" },
  { src: "대통령발", headline: '대통령 "K-방산 수출 전폭 지원"', time: "2시간 전" },
];
export function stockNews(name: string): NewsItem[] {
  return STOCK_NEWS[name] ?? DEFAULT_NEWS;
}
export function themeNews(t: CbTheme): NewsItem[] {
  return [
    { src: "속보", headline: t.news, time: "15분 전", hot: true },
    { src: t.name, headline: `${t.name} 대표주 강세…수급 집중`, time: "1시간 전" },
    { src: t.name, headline: `증권가 "${t.name} 모멘텀 지속" 진단`, time: "3시간 전" },
  ];
}

/** 전일 대비 등락금액(원) 근사 — 등락률로 역산. */
export function changeAmount(price: number, pct: number): number {
  const prev = Math.round(price / (1 + pct / 100));
  return price - prev;
}
