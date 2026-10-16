import type { LeaderTimelineResponse } from "../types";

export type TimelineMarket = "kr" | "us";

type Session = [start: number, end: number, label: string, regular?: boolean];

export interface MarketSpec {
  start: number;
  end: number;
  slots: number;
  gaps: [number, number][];
  sessionName: (localMin: number) => string;
}

export const MARKET_SPECS: Record<TimelineMarket, MarketSpec> = {
  kr: {
    start: 481,
    end: 1200,
    slots: 720,
    gaps: [[530, 540], [930, 940]],
    sessionName: (m) =>
      m < 530 ? "NXT 프리마켓" : m < 540 ? "쉬는 구간" : m < 930 ? "정규장" : m < 940 ? "쉬는 구간" : "NXT 애프터마켓",
  },
  us: {
    start: 240,
    end: 1200,
    slots: 961,
    gaps: [],
    sessionName: (m) => (m < 570 ? "프리마켓" : m < 960 ? "정규장" : "애프터마켓"),
  },
};

export const hourMarks = (spec: MarketSpec) =>
  [...Array(spec.slots).keys()].filter((i) => i === 0 || (spec.start + i) % 60 === 0);

const HOLD = 3;

export const hhmm = (m: number) => {
  const x = ((m % 1440) + 1440) % 1440;
  return `${String(Math.floor(x / 60)).padStart(2, "0")}:${String(x % 60).padStart(2, "0")}`;
};

export function kstOffsetFromNewYork(date: string): number {
  const noonUtc = new Date(`${date}T16:00:00Z`);
  const part = new Intl.DateTimeFormat("en-US", { timeZone: "America/New_York", timeZoneName: "shortOffset" })
    .formatToParts(noonUtc)
    .find((p) => p.type === "timeZoneName")?.value;
  const nyHours = Number((part ?? "GMT-4").replace("GMT", "")) || -4;
  return (9 - nyHours) * 60;
}

export const displayOffset = (market: TimelineMarket, date: string) =>
  market === "kr" ? 0 : kstOffsetFromNewYork(date);

export function sessionsOf(market: TimelineMarket, offset: number): Session[] {
  if (market === "kr")
    return [[481, 530, "프리"], [540, 930, "정규장 09:00–15:30", true], [940, 1200, "애프터 15:40–20:00"]];
  return [
    [240, 570, `프리 ${hhmm(240 + offset)}–${hhmm(570 + offset)}`],
    [570, 960, `정규장 ${hhmm(570 + offset)}–${hhmm(960 + offset)}`, true],
    [960, 1200, `애프터 ${hhmm(960 + offset)}–${hhmm(1200 + offset)}`],
  ];
}

export interface Entry {
  k: number;
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
  snap: (Entry[] | undefined)[];
  live: number[];
  last: number;
  rankAt: number[][];
  order: number[];
  events: TimelineEvent[];
  lineup: Map<number, number[]>;
  inSlots: number[][];
  names: string[];
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

  const member = [...Array(count).keys()].map((k) => smooth(live, (i) => rankAt[k][i] > 0));
  const rankS = [...Array(count).keys()].map((k) => smooth(live, (i) => rankAt[k][i]));
  const lineup = new Map<number, number[]>();
  const seen = new Array<number>(count).fill(6);
  live.forEach((i) => {
    for (let k = 0; k < count; k++) if (rankAt[k][i]) seen[k] = rankAt[k][i];
    const pos = (k: number) => rankS[k].get(i) || seen[k];
    const members = [...Array(count).keys()].filter((k) => member[k].get(i));
    members.sort((a, b) => pos(a) - pos(b) || (rankAt[a][i] || 6) - (rankAt[b][i] || 6) || a - b);
    lineup.set(i, members.slice(0, 5));
  });
  const inSlots = [...Array(count).keys()].map((k) => live.filter((i) => lineup.get(i)!.includes(k)));

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

export function nearestLive(model: TimelineModel, i: number): number {
  for (let j = Math.min(i, model.last); j >= 0; j--) if (model.snap[j]) return j;
  return model.live[0] ?? 0;
}

export function lineupAt(model: TimelineModel, t: number): number[] {
  for (let i = t; i >= 0; i--) {
    const l = model.lineup.get(i);
    if (l) return l;
  }
  return [];
}

export function entryAt(model: TimelineModel, k: number, t: number): Entry | null {
  for (let i = t; i >= Math.max(0, t - 5); i--) {
    const e = model.snap[i]?.find((x) => x.k === k);
    if (e) return e;
  }
  return null;
}

export function lastIn(model: TimelineModel, k: number, t: number): number {
  const a = model.inSlots[k] ?? [];
  for (let n = a.length - 1; n >= 0; n--) if (a[n] <= t) return a[n];
  return -1;
}

export function arrangement(model: TimelineModel, t: number) {
  const top = lineupAt(model, t);
  const rest = model.order
    .filter((k) => !top.includes(k) && lastIn(model, k, t) >= 0)
    .sort((a, b) => lastIn(model, b, t) - lastIn(model, a, t));
  const hidden = model.order.filter((k) => !top.includes(k) && !rest.includes(k));
  return { top, rest, hidden };
}

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

export interface RelayLeg {
  name: string;
  at: string;
}

export function leaderRelay(market: TimelineMarket, date: string, data: LeaderTimelineResponse): RelayLeg[] {
  const idx = data.ticks.map((_, n) => n);
  const leader = smooth(idx, (n) => data.ticks[n].stocks[0] ?? -1);
  const offset = displayOffset(market, date);
  const legs: RelayLeg[] = [];
  let prev = -1;
  for (const n of idx) {
    const k = leader.get(n)!;
    if (k < 0 || k === prev) continue;
    prev = k;
    const [h, m] = data.ticks[n].at.split(":").map(Number);
    legs.push({ name: data.stocks[k].name, at: hhmm(h * 60 + m + offset) });
  }
  return legs;
}

export function foldRelay(legs: RelayLeg[], max: number): { head: RelayLeg[]; folded: number; tail: RelayLeg[] } {
  if (legs.length <= max) return { head: legs, folded: 0, tail: [] };
  const folded = legs.length - max;
  return { head: legs.slice(0, 1), folded, tail: legs.slice(folded + 1) };
}
