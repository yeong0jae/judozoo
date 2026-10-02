/**
 * 모의 종가베팅 — 화면 확인용 예시 데이터와 종베 체크 규칙.
 *
 * 아직 백엔드가 없어 값은 전부 고정이다. 실제 연동 때 이 파일의 모양이 API 응답 모양의 초안이 된다.
 */

export type MarketName = "코스피" | "코스닥";

export type BetStock = {
  code: string;
  name: string;
  market: MarketName;
  lead: boolean; // 주도주(true) · 후보(false)
  price: number; // 당일은 현재가, 어제 판은 20:00 종가(= 매수가)
  chg: number; // 당일 등락률(%)
  high: number;
  low: number;
  frgn: number; // 당일 외국인 순매수(억원)
  inst: number;
  frgn5: number; // 최근 5일 누적
  inst5: number;
  tag: "" | "신고가" | "박스 돌파";
  crowd: number; // 고른 사람 수
  result?: number; // 어제 판 — 다음 날 아침 매도 수익률(%)
};

export const TODAY: BetStock[] = [
  { code: "000660", name: "SK하이닉스", market: "코스피", lead: true, price: 412500, chg: 6.82, high: 414000, low: 386000, frgn: 2640, inst: 910, frgn5: 8420, inst5: 2150, tag: "신고가", crowd: 41 },
  { code: "034020", name: "두산에너빌리티", market: "코스피", lead: true, price: 68900, chg: 5.19, high: 69400, low: 65400, frgn: 780, inst: 260, frgn5: 2310, inst5: 640, tag: "신고가", crowd: 27 },
  { code: "012450", name: "한화에어로스페이스", market: "코스피", lead: true, price: 1032000, chg: 4.13, high: 1044000, low: 991000, frgn: 520, inst: -140, frgn5: 1480, inst5: -520, tag: "신고가", crowd: 12 },
  { code: "196170", name: "알테오젠", market: "코스닥", lead: true, price: 486000, chg: 9.31, high: 505000, low: 444500, frgn: -210, inst: 450, frgn5: -650, inst5: 1210, tag: "박스 돌파", crowd: 9 },
  { code: "247540", name: "에코프로비엠", market: "코스닥", lead: false, price: 198400, chg: 7.86, high: 210200, low: 184000, frgn: -420, inst: -130, frgn5: -1830, inst5: -410, tag: "", crowd: 18 },
  { code: "267260", name: "HD현대일렉트릭", market: "코스피", lead: false, price: 512000, chg: 3.39, high: 522500, low: 494000, frgn: 230, inst: -80, frgn5: 540, inst5: -310, tag: "박스 돌파", crowd: 6 },
  { code: "277810", name: "레인보우로보틱스", market: "코스닥", lead: false, price: 341500, chg: 12.37, high: 346500, low: 302000, frgn: 110, inst: 60, frgn5: 260, inst5: 180, tag: "신고가", crowd: 14 },
  { code: "003230", name: "삼양식품", market: "코스피", lead: false, price: 1284000, chg: 2.56, high: 1296000, low: 1251000, frgn: 48, inst: -15, frgn5: -120, inst5: 95, tag: "", crowd: 4 },
];

export const YESTERDAY: BetStock[] = [
  { code: "000660", name: "SK하이닉스", market: "코스피", lead: true, price: 389000, chg: 3.12, high: 390500, low: 377000, frgn: 1950, inst: 420, frgn5: 6210, inst5: 1360, tag: "신고가", crowd: 48, result: 4.88 },
  { code: "034020", name: "두산에너빌리티", market: "코스피", lead: true, price: 64900, chg: 4.02, high: 65200, low: 62300, frgn: 610, inst: 150, frgn5: 1890, inst5: 420, tag: "신고가", crowd: 31, result: 6.13 },
  { code: "012450", name: "한화에어로스페이스", market: "코스피", lead: true, price: 991000, chg: 2.48, high: 1003000, low: 966000, frgn: 330, inst: -90, frgn5: 1130, inst5: -380, tag: "", crowd: 17, result: 3.51 },
  { code: "196170", name: "알테오젠", market: "코스닥", lead: true, price: 444000, chg: 6.7, high: 452000, low: 416000, frgn: -80, inst: 310, frgn5: -520, inst5: 880, tag: "박스 돌파", crowd: 9, result: 5.31 },
  { code: "247540", name: "에코프로비엠", market: "코스닥", lead: false, price: 184500, chg: 5.2, high: 189000, low: 175400, frgn: 120, inst: -60, frgn5: -980, inst5: -250, tag: "", crowd: 22, result: 2.1 },
  { code: "267260", name: "HD현대일렉트릭", market: "코스피", lead: false, price: 498000, chg: 1.85, high: 507000, low: 489000, frgn: -140, inst: 40, frgn5: -310, inst5: -150, tag: "", crowd: 8, result: -0.82 },
  { code: "277810", name: "레인보우로보틱스", market: "코스닥", lead: false, price: 298000, chg: 8.15, high: 298500, low: 275500, frgn: 90, inst: 55, frgn5: 190, inst5: 120, tag: "신고가", crowd: 6, result: 14.2 },
  { code: "003230", name: "삼양식품", market: "코스피", lead: false, price: 1262000, chg: 1.1, high: 1281000, low: 1248000, frgn: -30, inst: 12, frgn5: -160, inst5: 70, tag: "", crowd: 7, result: -1.46 },
];

/** 시장 수급 — [현물 당일, 현물 5일, 선물 당일, 선물 5일, 마감 구간, 애프터] (현물 억원 · 선물 계약) */
export type MarketFlow = { foreign: number[]; institution: number[] };
export const MARKET: Record<"today" | "yesterday", Record<MarketName, MarketFlow>> = {
  today: {
    코스피: { foreign: [4820, 12340, 3412, 8905, 590, 60], institution: [1150, -2860, -1208, -4410, 130, -40] },
    코스닥: { foreign: [610, -1240, 842, 1530, -15, -5], institution: [-230, 480, -315, -620, -20, -10] },
  },
  yesterday: {
    코스피: { foreign: [3980, 9870, 2105, 6420, 410, 85], institution: [-620, -1940, -870, -3150, 95, -20] },
    코스닥: { foreign: [-210, -1450, 380, 980, 30, 12], institution: [140, 520, -190, -410, 8, -6] },
  },
};

export const NIGHT_FUTURES = {
  today: { price: 413.6, rate: 0.35, note: "어젯밤 마감" },
  yesterday: { price: 413.6, rate: 0.35, note: "9/30 밤 마감" },
};

/** 베팅 피드 — 금액은 만원. 음수는 취소 */
export const FEED: { nick: string; stock: string; amount: number }[] = [
  { nick: "새벽올빼미", stock: "SK하이닉스", amount: 5000 },
  { nick: "번개토끼", stock: "레인보우로보틱스", amount: 10000 },
  { nick: "조용한고래", stock: "두산에너빌리티", amount: 2000 },
  { nick: "반짝이는별", stock: "알테오젠", amount: 500 },
  { nick: "조용한고래", stock: "두산에너빌리티", amount: -2000 },
  { nick: "느긋한판다", stock: "SK하이닉스", amount: 10000 },
  { nick: "단단한거북", stock: "한화에어로스페이스", amount: 1000 },
  { nick: "날쌘치타", stock: "에코프로비엠", amount: 5000 },
  { nick: "푸른돌고래", stock: "HD현대일렉트릭", amount: 100 },
  { nick: "졸린수달", stock: "SK하이닉스", amount: 2000 },
  { nick: "용감한판다", stock: "삼양식품", amount: 500 },
];

/** 종목별 몰린 판돈(만원) */
export const POT_TODAY: Record<string, number> = { SK하이닉스: 142000, 두산에너빌리티: 79000, 한화에어로스페이스: 41000, 알테오젠: 33000, 에코프로비엠: 56000, HD현대일렉트릭: 14000, 레인보우로보틱스: 39000, 삼양식품: 8000 };
export const POT_YESTERDAY: Record<string, number> = { SK하이닉스: 186000, 두산에너빌리티: 112000, 한화에어로스페이스: 61000, 알테오젠: 34000, 에코프로비엠: 78000, HD현대일렉트릭: 19000, 레인보우로보틱스: 21000, 삼양식품: 16000 };

export type RankRow = { nick: string; stock: string; profit: string; rate: string };
export const RANK_PROFIT: RankRow[] = [
  { nick: "새벽올빼미", stock: "두산에너빌리티", profit: "+612.4만", rate: "+6.13%" },
  { nick: "느긋한판다", stock: "SK하이닉스", profit: "+488.0만", rate: "+4.88%" },
  { nick: "단단한거북", stock: "한화에어로스페이스", profit: "+351.2만", rate: "+3.51%" },
  { nick: "조용한고래", stock: "SK하이닉스", profit: "+244.0만", rate: "+4.88%" },
  { nick: "날쌘치타", stock: "두산에너빌리티", profit: "+183.9만", rate: "+6.13%" },
];
export const RANK_RATE: RankRow[] = [
  { nick: "번개토끼", stock: "레인보우로보틱스", profit: "+28.4만", rate: "+14.20%" },
  { nick: "새벽올빼미", stock: "두산에너빌리티", profit: "+612.4만", rate: "+6.13%" },
  { nick: "날쌘치타", stock: "두산에너빌리티", profit: "+183.9만", rate: "+6.13%" },
  { nick: "반짝이는별", stock: "알테오젠", profit: "+53.1만", rate: "+5.31%" },
  { nick: "느긋한판다", stock: "SK하이닉스", profit: "+488.0만", rate: "+4.88%" },
];

/** 닉네임 — 2~10자, 한글·영문·숫자만. 바꾸면 7일 뒤에 다시 바꿀 수 있다 */
export const NICKNAME_RULE = /^[가-힣a-zA-Z0-9]{2,10}$/;
export const NICKNAME_COOLDOWN_DAYS = 7;

/** 어제 내 종베 */
export const MY_YESTERDAY = { code: "247540", amount: 2000, shares: 108, pnl: 418500, rate: 2.1, profitRank: 23, rateRank: 31, players: 148, gapToAbove: 3200 };

// ============================================================
// 종베 체크
// ============================================================

export type Check = {
  key: "high" | "foreign" | "institution" | "late" | "level";
  title: string;
  question: string;
  criteria: string;
  ok: boolean;
  value: string;
  sentence: string;
  why: string;
  range?: { low: number; high: number; pos: number; zoneStart: number };
  flows?: { label: string; value: number; max: number }[];
};

const NEAR_HIGH = -1.5;
const won = (n: number) => Math.round(n).toLocaleString("ko-KR");
const signed = (n: number) => `${n > 0 ? "+" : ""}${won(n)}`;

/** 이 종목이 속한 시장의 마감·애프터 외인+기관 합 */
export function lateSum(flow: MarketFlow): number {
  return flow.foreign[4] + flow.foreign[5] + flow.institution[4] + flow.institution[5];
}

export function checksOf(x: BetStock, list: BetStock[], markets: Record<MarketName, MarketFlow>): Check[] {
  const fromHigh = (x.price / x.high - 1) * 100;
  const near = fromHigh >= NEAR_HIGH;
  const maxAbs = (k: "frgn" | "inst" | "frgn5" | "inst5") => Math.max(...list.map((s) => Math.abs(s[k]))) || 1;

  const flowCheck = (who: "외국인" | "기관", k: "frgn" | "inst"): Check => {
    const v = x[k];
    const v5 = x[k === "frgn" ? "frgn5" : "inst5"];
    const week =
      v > 0 && v5 > 0 ? ` 최근 5일로도 ${won(v5)}억 순매수라, 며칠째 사 모으고 있어요.`
      : v > 0 ? ` 다만 최근 5일로는 ${won(-v5)}억 순매도라, 오늘 막 방향을 바꿨어요.`
      : v5 > 0 ? ` 최근 5일로는 ${won(v5)}억 순매수였는데, 오늘은 팔았어요.`
      : ` 최근 5일로도 ${won(-v5)}억 순매도라, 계속 팔고 있어요.`;
    return {
      key: k === "frgn" ? "foreign" : "institution",
      title: who,
      question: `${who}이 샀나요?`,
      criteria: "오늘 순매수 (KRX+NXT)",
      ok: v > 0,
      value: `${signed(v)}억`,
      sentence: `오늘 ${who}이 ${won(Math.abs(v))}억어치 더 ${v > 0 ? "샀어요" : "팔았어요"}.${week}`,
      why: "판정은 오늘 순매수로 해요. 5일 흐름이 같은 방향이면 더 믿을 만해요.",
      flows: [
        { label: "오늘", value: v, max: maxAbs(k) },
        { label: "5일", value: v5, max: maxAbs(k === "frgn" ? "frgn5" : "inst5") },
      ],
    };
  };

  const flow = markets[x.market];
  const sum = lateSum(flow);
  const late: Check = {
    key: "late",
    title: "시장 막판",
    question: "시장도 막판에 사들였나요?",
    criteria: "시장 마감·애프터 외인+기관 합 플러스",
    ok: sum > 0,
    value: `${x.market} ${signed(sum)}억`,
    sentence: `${x.market}에서 마감 구간과 애프터에 외국인·기관이 합쳐 ${won(Math.abs(sum))}억 ${sum > 0 ? "순매수" : "순매도"}했어요. (외인 ${signed(flow.foreign[4] + flow.foreign[5])}억 · 기관 ${signed(flow.institution[4] + flow.institution[5])}억)`,
    why: "이 종목이 속한 시장의 마감·애프터 외인+기관 합이 플러스면 맞아요.",
  };

  return [
    {
      key: "high",
      title: "고가 마감",
      question: "고가 근처에서 끝났나요?",
      criteria: `고가 대비 ${NEAR_HIGH}% 이내`,
      ok: near,
      value: `${fromHigh.toFixed(1)}%`,
      sentence: near ? "오늘 고가 바로 아래에서 버티고 있어요. 윗꼬리가 짧아요." : `고가에서 ${Math.abs(fromHigh).toFixed(1)}% 밀려 내려왔어요. 윗꼬리가 길어요.`,
      why: "장 막판까지 고가를 지킨 종목은 다음 날 아침 매물이 적어요. 초록 구간(고가 -1.5% 안)이면 맞아요.",
      range: { low: x.low, high: x.high, pos: (x.price - x.low) / (x.high - x.low), zoneStart: (x.high * (1 + NEAR_HIGH / 100) - x.low) / (x.high - x.low) },
    },
    flowCheck("외국인", "frgn"),
    flowCheck("기관", "inst"),
    late,
    {
      key: "level",
      title: "신고가",
      question: "위가 뚫린 자리인가요?",
      criteria: "52주 신고가 또는 박스 돌파",
      ok: !!x.tag,
      value: x.tag || "아니에요",
      sentence: x.tag === "신고가" ? "52주 신고가를 새로 썼어요. 위에 물려서 팔려는 사람이 없어요." : x.tag ? "한동안 막혀 있던 가격대를 뚫고 올라섰어요." : "위쪽에 예전에 물린 매물이 남아 있어요.",
      why: "위에 매물이 없으면 조금만 사도 가격이 잘 올라가요.",
    },
  ];
}

// ============================================================
// 등급 — 다섯 가지 중 몇 개를 채웠나. 금·은·동·철
// ============================================================

export type Grade = "S" | "A" | "B" | "C";
export function gradeOf(passed: number): Grade {
  return passed >= 5 ? "S" : passed === 4 ? "A" : passed === 3 ? "B" : "C";
}
export const GRADE_COLOR: Record<Grade, string> = { S: "#f5c451", A: "#d4d8de", B: "#c98a5a", C: "#8b95a1" };
export const GRADE_TEXT: Record<Grade, string> = { S: "다섯 가지 모두 맞아요", A: "네 가지가 맞아요", B: "세 가지가 맞아요", C: "맞는 게 두 개 이하예요" };
/** 티켓 머리 칸 물들이기 */
export const GRADE_TINT: Record<Grade, string> = { S: "rgba(245,196,81,0.22)", A: "rgba(212,216,222,0.16)", B: "rgba(201,138,90,0.20)", C: "rgba(91,99,110,0.22)" };
export const GRADE_PERF: Record<Grade, string> = { S: "rgba(245,196,81,0.55)", A: "rgba(212,216,222,0.5)", B: "rgba(201,138,90,0.55)", C: "rgba(107,116,128,0.5)" };

export const nxtListed = (code: string) => code !== "277810";
