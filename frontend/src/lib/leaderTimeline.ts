import type { LeaderTimelineResponse } from "../types";

/**
 * 주도주 타임라인 계산 — 화면은 이 결과만 그린다.
 *
 * 슬롯 = 세션 시작부터 1분 칸. 분은 **현지 시각**(국내 KST, 해외 뉴욕)이고, 화면 표시는 한국 시간이다.
 * 1분 원본은 5위 경계에서 들락날락이 심해서, 줄 순서·배지·변화 기록은 **3분 넘게 이어진 값**으로 다듬는다.
 * 띠(타임라인)만 원본을 쓴다.
 */

export type TimelineMarket = "kr" | "us";

type Session = [start: number, end: number, label: string, regular?: boolean];

export interface MarketSpec {
  start: number; // 현지 분
  end: number;
  /** 시작부터 마감 분까지 1분 칸 수 — 마감 분도 한 칸이라 end − start + 1 */
  slots: number;
  gaps: [number, number][];
  sessionName: (localMin: number) => string;
}

export const MARKET_SPECS: Record<TimelineMarket, MarketSpec> = {
  kr: {
    start: 480,
    end: 1200,
    slots: 721,
    // 단일가·장 전환 구간 — 찍지 않는다(백엔드 `leadertimeline.domain`과 같다)
    gaps: [[530, 540], [930, 940]],
    sessionName: (m) =>
      m < 530 ? "NXT 프리마켓" : m < 540 ? "쉬는 구간" : m < 930 ? "정규장" : m < 940 ? "쉬는 구간" : "NXT 애프터마켓",
  },
  // 애프터마켓까지 — 실적 발표가 대개 정규장 마감 직후다(백엔드 `market.calendar`와 같다)
  us: {
    start: 240,
    end: 1200,
    slots: 961,
    gaps: [],
    sessionName: (m) => (m < 570 ? "프리마켓" : m < 960 ? "정규장" : "애프터마켓"),
  },
};

/** 흔들림으로 보지 않는 최소 지속 — 이만큼 이어져야 바뀐 것으로 친다 */
const HOLD = 3;

export const hhmm = (m: number) => {
  const x = ((m % 1440) + 1440) % 1440;
  return `${String(Math.floor(x / 60)).padStart(2, "0")}:${String(x % 60).padStart(2, "0")}`;
};

/** 뉴욕이 한국보다 몇 분 늦은가 — 서머타임이면 780(13시간), 아니면 840. 그날 정오 기준 */
export function kstOffsetFromNewYork(date: string): number {
  const noonUtc = new Date(`${date}T16:00:00Z`);
  const part = new Intl.DateTimeFormat("en-US", { timeZone: "America/New_York", timeZoneName: "shortOffset" })
    .formatToParts(noonUtc)
    .find((p) => p.type === "timeZoneName")?.value; // "GMT-4"
  const nyHours = Number((part ?? "GMT-4").replace("GMT", "")) || -4;
  return (9 - nyHours) * 60;
}

/** 표시 시각(한국)으로 옮기는 분 차이 — 국내는 0 */
export const displayOffset = (market: TimelineMarket, date: string) =>
  market === "kr" ? 0 : kstOffsetFromNewYork(date);

/** 세션 띠 — 해외는 한국 시간으로 적는다 */
export function sessionsOf(market: TimelineMarket, offset: number): Session[] {
  if (market === "kr")
    return [[480, 530, "프리"], [540, 930, "정규장 09:00–15:30", true], [940, 1200, "애프터 15:40–20:00"]];
  return [
    [240, 570, `프리 ${hhmm(240 + offset)}–${hhmm(570 + offset)}`],
    [570, 960, `정규장 ${hhmm(570 + offset)}–${hhmm(960 + offset)}`, true],
    [960, 1200, `애프터 ${hhmm(960 + offset)}–${hhmm(1200 + offset)}`],
  ];
}

export interface Entry {
  k: number; // 종목 번호
  rank: number;
  rate: number;
  value: number;
}

export type TimelineEvent =
  | { i: number; type: "in"; k: number; r: number }
  | { i: number; type: "out"; k: number }
  | { i: number; type: "top"; k: number }
  | { i: number; type: "none" };

export interface TimelineModel {
  spec: MarketSpec;
  /** snap[i] — 찍은 분이면 배열(0개면 빈 배열), 안 찍힌 분은 undefined */
  snap: (Entry[] | undefined)[];
  /** 찍은 분들, 시각 순 */
  live: number[];
  last: number; // 마지막으로 찍은 슬롯(-1 = 없음)
  rankAt: number[][]; // rankAt[k][i] = 1..5 또는 0 (원본)
  /** 종목 번호 목록 — 오늘 많이 주도한 순(머문 시간 × 순위). 띠 순서의 기본값 */
  order: number[];
  events: TimelineEvent[];
  /** 슬롯 → 다듬은 5종목(순위 순) */
  lineup: Map<number, number[]>;
  /** 종목별로 다듬은 5종목 안에 있던 슬롯들 */
  inSlots: number[][];
  /** 종목 이름 — 번호로 찾는다 */
  names: string[];
  /** 종목별 띠 조각 — 한 번만 계산해 둔다(마우스를 움직일 때마다 다시 만들지 않게) */
  segs: Segment[][];
}

export interface Segment {
  start: number;
  end: number;
  rank: number;
  roundStart: boolean;
  roundEnd: boolean;
}

export const isGap = (spec: MarketSpec, i: number) => {
  const m = spec.start + i;
  return spec.gaps.some(([a, b]) => m >= a && m < b);
};

/** 값이 바뀐 뒤 HOLD칸 동안 그대로여야 바뀐 것으로 친다 */
function smooth<T>(live: number[], raw: (i: number) => T): Map<number, T> {
  const out = new Map<number, T>();
  if (!live.length) return out;
  let state = raw(live[0]);
  live.forEach((i, n) => {
    const v = raw(i);
    if (v !== state && live.slice(n, n + HOLD).every((j) => raw(j) === v)) state = v;
    out.set(i, state);
  });
  return out;
}

export function buildModel(market: TimelineMarket, data: LeaderTimelineResponse): TimelineModel {
  const spec = MARKET_SPECS[market];
  const snap: (Entry[] | undefined)[] = new Array(spec.slots).fill(undefined);
  for (const t of data.ticks) {
    const [h, m] = t.at.split(":").map(Number);
    const i = h * 60 + m - spec.start;
    if (i < 0 || i >= spec.slots) continue;
    snap[i] = t.stocks.map((k, n) => ({ k, rank: n + 1, rate: t.rates[n], value: t.values[n] }));
  }
  const live = snap.flatMap((s, i) => (s ? [i] : []));
  const last = live.length ? live[live.length - 1] : -1;
  const count = data.stocks.length;

  const rankAt = Array.from({ length: count }, () => new Array<number>(spec.slots).fill(0));
  snap.forEach((s, i) => s?.forEach((e) => (rankAt[e.k][i] = e.rank)));
  const firstIn = rankAt.map((r) => r.findIndex((x) => x > 0));
  const lead = rankAt.map((r) => r.reduce((sum, v) => sum + (v ? 6 - v : 0), 0));
  const order = [...Array(count).keys()]
    .filter((k) => firstIn[k] >= 0)
    .sort((a, b) => lead[b] - lead[a] || firstIn[a] - firstIn[b]);

  const key = (i: number) => snap[i]!.map((e) => e.k).join(",");
  const lineupRaw = smooth(live, key);
  const lineup = new Map<number, number[]>();
  lineupRaw.forEach((v, i) => lineup.set(i, v ? v.split(",").map(Number) : []));
  const inSlots = [...Array(count).keys()].map((k) => live.filter((i) => lineup.get(i)!.includes(k)));

  // 변화 기록 — 종목마다 "5종목 안에 있음"을 다듬고, 그 상태가 바뀐 순간만 남긴다(들어옴·빠짐이 짝을 이룬다)
  const member = [...Array(count).keys()].map((k) => smooth(live, (i) => snap[i]!.some((e) => e.k === k)));
  const leader = smooth(live, (i) => (snap[i]!.length ? snap[i]![0].k : -1));
  const events: TimelineEvent[] = [];
  live.forEach((i, n) => {
    if (n === 0) {
      snap[i]!.forEach((e) => events.push({ i, type: "in", k: e.k, r: e.rank }));
      return;
    }
    const p = live[n - 1];
    for (let k = 0; k < count; k++) {
      const was = member[k].get(p), is = member[k].get(i);
      if (!was && is) events.push({ i, type: "in", k, r: snap[i]!.find((e) => e.k === k)?.rank ?? 5 });
      if (was && !is) events.push({ i, type: "out", k });
    }
    const l = leader.get(i)!;
    if (l !== leader.get(p)) events.push(l === -1 ? { i, type: "none" } : { i, type: "top", k: l });
  });

  const model = { spec, snap, live, last, rankAt, order, events, lineup, inSlots, names: data.stocks.map((s) => s.name), segs: [] as Segment[][] };
  model.segs = [...Array(count).keys()].map((k) => segmentsOf(model, k));
  return model;
}

/** t 시각(안 찍혔으면 그 앞 가장 가까운 분)의 다듬은 5종목, 순위 순 */
export function lineupAt(model: TimelineModel, t: number): number[] {
  for (let i = t; i >= 0; i--) {
    const l = model.lineup.get(i);
    if (l) return l;
  }
  return [];
}

/** 종목의 t 시각 값 — 다듬은 줄엔 있는데 그 1분엔 5위 밖이었으면 바로 앞 값 */
export function entryAt(model: TimelineModel, k: number, t: number): Entry | null {
  for (let i = t; i >= Math.max(0, t - 5); i--) {
    const e = model.snap[i]?.find((x) => x.k === k);
    if (e) return e;
  }
  return null;
}

/** k가 t 시각까지 마지막으로 주도주였던 슬롯. 한 번도 아니었으면 -1 */
export function lastIn(model: TimelineModel, k: number, t: number): number {
  const a = model.inSlots[k] ?? [];
  for (let n = a.length - 1; n >= 0; n--) if (a[n] <= t) return a[n];
  return -1;
}

/** 줄 배치 — 위는 t 시각의 5종목(순위 순), 아래는 t까지 주도주였다가 빠진 종목(최근에 빠진 순).
 *  아직 등장하지 않은 종목은 숨긴다 */
export function arrangement(model: TimelineModel, t: number) {
  const top = lineupAt(model, t);
  const rest = model.order
    .filter((k) => !top.includes(k) && lastIn(model, k, t) >= 0)
    .sort((a, b) => lastIn(model, b, t) - lastIn(model, a, t));
  const hidden = model.order.filter((k) => !top.includes(k) && !rest.includes(k));
  return { top, rest, hidden };
}

/** 슬롯 → 원본 순위가 같은 연속 구간(띠 한 조각). 쉬는 구간·안 찍힌 분에서 끊긴다 */
export function segmentsOf(model: Pick<TimelineModel, "rankAt">, k: number): Segment[] {
  const r = model.rankAt[k];
  const out: Segment[] = [];
  let i = 0;
  while (i < r.length) {
    if (!r[i]) { i++; continue; }
    let j = i;
    while (j + 1 < r.length && r[j + 1] === r[i]) j++;
    out.push({ start: i, end: j + 1, rank: r[i], roundStart: !r[i - 1], roundEnd: !r[j + 1] });
    i = j + 1;
  }
  return out;
}
