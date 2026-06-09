import type { RegimeDaily } from "../types";

// 4구간 메타 — 직전 시점 대비. prev = 이 구간의 기준(직전 시점) 라벨.
export const REGIME_SEGMENTS = [
  { key: "오전 NXT", prev: "전일 20:00", color: "#38bdf8", from: "전일 20:00", to: "08:15" },
  { key: "오전 정규장", prev: "오전 NXT", color: "#a78bfa", from: "08:15", to: "11:00" },
  { key: "오후 정규장", prev: "오전 정규장", color: "#fbbf24", from: "11:00", to: "14:00" },
  { key: "오후 NXT", prev: "오후 정규장", color: "#f472b6", from: "14:00", to: "20:00" },
] as const;

export type RegimeRow = { date: string; segs: (number | null)[] };

/** 같은 기준(08:15) 대비 두 값에서 from→to 변동(%) */
export function rel(from: number, to: number): number {
  return ((1 + to / 100) / (1 + from / 100) - 1) * 100;
}

/**
 * 시간순(오래된 게 앞) 입력 → 각 날의 4구간 변동(%).
 * gap1(오전 NXT)은 백엔드에서 이미 **전일 20:00(NXT 마감) 기준**으로 산출됨.
 * 각 구간은 해당 시각이 지나야 값이 생긴다(없으면 null).
 */
export function computeRows(chrono: RegimeDaily[]): RegimeRow[] {
  return chrono.map((d) => ({
    date: d.date,
    segs: [
      d.gap1, // 오전 NXT (전일 20:00 → 08:15)
      d.gap2At1100, // 08:15 → 11:00
      d.gap2At1100 !== null && d.gap2At1400 !== null ? rel(d.gap2At1100, d.gap2At1400) : null,
      d.gap2At1400 !== null && d.gap2At2000 !== null ? rel(d.gap2At1400, d.gap2At2000) : null,
    ],
  }));
}
