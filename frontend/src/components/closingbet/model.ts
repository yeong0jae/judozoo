export type MarketName = "코스피" | "코스닥";

export type BetStock = {
  code: string;
  name: string;
  market: MarketName;
  lead: boolean;
  price: number;
  chg: number;
  high: number;
  low: number;
  frgn: number;
  inst: number;
  frgn5: number;
  inst5: number;
  recentHighGap: number | null;
  regularClose: number | null;
  crowd: number;
  result?: number;
};

export type MarketFlow = { foreign: number[]; institution: number[] };

export const NICKNAME_RULE = /^[가-힣a-zA-Z0-9]{2,10}$/;
export const NICKNAME_COOLDOWN_DAYS = 7;

export type Check = {
  key: "high" | "foreign" | "institution" | "late" | "recentHigh" | "after";
  title: string;
  criteria: string;
  ok: boolean;
  value: string;
  sentence: string;
  scale?: { value: number; threshold: number };
};

const NEAR_HIGH = -1.5;
const RECENT_HIGH_FLOOR = -5;
const won = (n: number) => Math.round(n).toLocaleString("ko-KR");
const signed = (n: number) => `${n > 0 ? "+" : ""}${won(n)}`;

export function lateSum(flow: MarketFlow): number {
  return flow.foreign[4] + flow.foreign[5] + flow.institution[4] + flow.institution[5];
}

export function checksOf(x: BetStock, markets: Record<MarketName, MarketFlow>): Check[] {
  const fromHigh = (x.price / x.high - 1) * 100;
  const gap = x.recentHighGap;
  const after = x.regularClose ? (x.price / x.regularClose - 1) * 100 : null;
  const near = fromHigh >= NEAR_HIGH;

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
      criteria: "오늘 순매수 (KRX+NXT)",
      ok: v > 0,
      value: `${signed(v)}억 · 5일 ${signed(v5)}억`,
      sentence: `오늘 ${won(Math.abs(v))}억어치 더 ${v > 0 ? "샀어요" : "팔았어요"}.${week}`,
    };
  };

  const flow = markets[x.market];
  const sum = lateSum(flow);

  return [
    {
      key: "high",
      title: "고가 마감",
      criteria: `고가 대비 ${NEAR_HIGH}% 안`,
      ok: near,
      value: `${fromHigh.toFixed(1)}%`,
      sentence: near ? "오늘 고가 바로 아래에서 버텨 다음 날 아침 매물이 적어요." : `고가에서 ${Math.abs(fromHigh).toFixed(1)}% 밀려 윗꼬리가 길어요.`,
      scale: { value: fromHigh, threshold: NEAR_HIGH },
    },
    {
      key: "recentHigh",
      title: "구간 신고가 (60거래일)",
      criteria: `최근 60거래일 고가 대비 ${RECENT_HIGH_FLOOR}% 안`,
      ok: gap !== null && gap >= RECENT_HIGH_FLOOR,
      value: gap === null ? "—" : `${gap > 0 ? "+" : ""}${gap.toFixed(1)}%`,
      sentence:
        gap === null
          ? "최근 일봉이 없어 재지 못했어요."
          : gap >= 0
            ? "최근 60거래일 고점을 넘어서 위에서 본전에 팔려는 물량이 없어요."
            : `최근 60거래일 고점까지 ${Math.abs(gap).toFixed(1)}% 남았어요. 고점 근처라야 위에서 본전에 팔려는 물량이 적어요.`,
      scale: gap === null ? undefined : { value: gap, threshold: RECENT_HIGH_FLOOR },
    },
    flowCheck("외국인", "frgn"),
    flowCheck("기관", "inst"),
    {
      key: "late",
      title: "마감 부근 수급",
      criteria: "시장 마감·애프터 외인+기관 합 플러스",
      ok: sum > 0,
      value: `${x.market} ${signed(sum)}억`,
      sentence: `마감 구간과 애프터에 외인 ${signed(flow.foreign[4] + flow.foreign[5])}억 · 기관 ${signed(flow.institution[4] + flow.institution[5])}억.`,
    },
    {
      key: "after",
      title: "애프터 버팀",
      criteria: "20:00 종가가 15:30 정규장 종가 이상",
      ok: after !== null && after >= 0,
      value: after === null ? "15:30 뒤" : `${after > 0 ? "+" : ""}${after.toFixed(1)}%`,
      sentence:
        after === null || x.regularClose === null
          ? "15:30 정규장이 끝나면 애프터 흐름을 봐요."
          : after >= 0
            ? `정규장 종가 ${won(x.regularClose)}원에서 밀리지 않았어요. 애프터에서도 사는 사람이 있어요.`
            : `정규장 종가 ${won(x.regularClose)}원에서 ${Math.abs(after).toFixed(1)}% 밀렸어요. 다음 날 아침에도 약할 수 있어요.`,
    },
  ];
}

export type Verdict = { nearHigh: boolean; foreign: boolean; institution: boolean; marketLate: boolean; recentHigh: boolean; afterHold: boolean };

const VERDICT_KEY: Record<Check["key"], keyof Verdict> = { high: "nearHigh", foreign: "foreign", institution: "institution", late: "marketLate", recentHigh: "recentHigh", after: "afterHold" };

export function withVerdict(checks: Check[], verdict: Verdict): Check[] {
  return checks.map((c) => ({ ...c, ok: verdict[VERDICT_KEY[c.key]] }));
}

export type Grade = "S" | "A" | "B" | "C";
export const GRADE_COLOR: Record<Grade, string> = { S: "#f5c451", A: "#d4d8de", B: "#c98a5a", C: "#8b95a1" };
export const GRADE_TEXT: Record<Grade, string> = { S: "여섯 가지 모두 맞아요", A: "다섯 가지가 맞아요", B: "네 가지가 맞아요", C: "맞는 게 세 개 이하예요" };
export const GRADE_TINT: Record<Grade, string> = { S: "rgba(245,196,81,0.22)", A: "rgba(212,216,222,0.16)", B: "rgba(201,138,90,0.20)", C: "rgba(91,99,110,0.22)" };
export const GRADE_PERF: Record<Grade, string> = { S: "rgba(245,196,81,0.55)", A: "rgba(212,216,222,0.5)", B: "rgba(201,138,90,0.55)", C: "rgba(107,116,128,0.5)" };
