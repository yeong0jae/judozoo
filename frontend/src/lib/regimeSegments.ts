import type { RegimeDaily } from "../types";

// 4구간 메타 — 직전 시점 대비. prev = 이 구간의 기준(직전 시점) 라벨.
export const REGIME_SEGMENTS = [
  { key: "아침 NXT", prev: "전일 20:00", color: "#38bdf8", from: "전일 20:00", to: "08:15" },
  { key: "오전 정규장", prev: "아침 NXT", color: "#a78bfa", from: "08:15", to: "10:00" },
  { key: "오후 정규장", prev: "오전 정규장", color: "#fbbf24", from: "10:00", to: "15:30" },
  { key: "오후 NXT", prev: "오후 정규장", color: "#f472b6", from: "15:30", to: "20:00" },
] as const;

export type RegimeRow = { date: string; segs: (number | null)[] };

/** 같은 기준(08:15) 대비 두 값에서 from→to 변동(%) */
function rel(from: number, to: number): number {
  return ((1 + to / 100) / (1 + from / 100) - 1) * 100;
}

/** 전일 15:30→20:00(오후 NXT) 변동 — 다음날 아침 NXT 기준 보정용. */
function afterMarketOf(r: RegimeDaily | null): number | null {
  if (!r || r.gap2At1530 === null || r.gap2At2000 === null) return null;
  return rel(r.gap2At1530, r.gap2At2000);
}

/**
 * 시간순(오래된 게 앞) 입력 → 각 날의 4구간 변동(%).
 * 아침 NXT는 **전일 20:00(NXT 마감) 대비 08:15** — 전날 오후 NXT만큼 보정해 흐름을 연속으로 잇는다.
 * 각 구간은 해당 시각이 지나야 값이 생긴다(없으면 null).
 */
export function computeRows(chrono: RegimeDaily[]): RegimeRow[] {
  return chrono.map((d, i) => {
    const prevAfter = afterMarketOf(i > 0 ? chrono[i - 1] : null);
    // 전일 20:00 데이터가 있으면 그 기준으로 보정, 없으면(가장 오래된 날 등) 저장값(전일 15:30 기준) 사용
    const morningNxt = prevAfter === null ? d.gap1 : rel(prevAfter, d.gap1);
    return {
      date: d.date,
      segs: [
        morningNxt,
        d.gap2At1000, // 08:15 → 10:00
        d.gap2At1000 !== null && d.gap2At1530 !== null ? rel(d.gap2At1000, d.gap2At1530) : null,
        d.gap2At1530 !== null && d.gap2At2000 !== null ? rel(d.gap2At1530, d.gap2At2000) : null,
      ],
    };
  });
}
