import type { RegimeDaily } from "../types";

// 4구간 메타 — 직전 시점 대비. prev = 이 구간의 기준(직전 시점) 라벨.
export const REGIME_SEGMENTS = [
  { key: "아침 NXT", prev: "전일", color: "#38bdf8", from: "전일종가", to: "08:15" },
  { key: "오전 정규장", prev: "아침 NXT", color: "#a78bfa", from: "08:15", to: "10:00" },
  { key: "오후 정규장", prev: "오전 정규장", color: "#fbbf24", from: "10:00", to: "15:30" },
  { key: "오후 NXT", prev: "오후 정규장", color: "#f472b6", from: "15:30", to: "20:00" },
] as const;

function rel(from: number, to: number): number {
  return ((1 + to / 100) / (1 + from / 100) - 1) * 100;
}

/** 하루치 → 4구간 변동(%) (직전 시점 대비). 데이터 없으면 null. */
export function regimeSegments(d: RegimeDaily): (number | null)[] {
  const close1530 = d.gap2At1530 ?? d.gap2Close; // 구 시드행(1530 없음) 폴백
  return [
    d.gap1,
    d.gap2At1000,
    d.gap2At1000 === null ? null : rel(d.gap2At1000, close1530),
    d.gap2At2000 === null ? null : rel(close1530, d.gap2At2000),
  ];
}
