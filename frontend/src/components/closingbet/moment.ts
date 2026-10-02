/**
 * 시간대 — 복기 09:05~15:00 · 베팅 15:00~20:00 · 대기 20:00~08:00 · 결과 08:00~09:05 · 휴장.
 * 지금이 어느 칸인지는 서버가 정한다(`/api/closingbet/now`). 여기는 표시만.
 */
export type Moment = "review" | "bet" | "night" | "result" | "holiday";

export function countdown(to: Date, now: Date): string {
  const left = Math.max(0, Math.floor((to.getTime() - now.getTime()) / 1000));
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(Math.floor(left / 3600))}:${p(Math.floor((left % 3600) / 60))}:${p(left % 60)}`;
}

/** "10/5(월)" */
export function dayLabel(d: Date): string {
  return `${d.getMonth() + 1}/${d.getDate()}(${"일월화수목금토"[d.getDay()]})`;
}

/** 방금 · n초 전 · n분 전 */
export function ago(at: Date, now: Date): string {
  const s = Math.max(0, Math.floor((now.getTime() - at.getTime()) / 1000));
  if (s < 5) return "방금";
  if (s < 60) return `${s}초 전`;
  return `${Math.floor(s / 60)}분 전`;
}
