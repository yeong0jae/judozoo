export type Moment = "review" | "bet" | "night" | "result";

export function countdown(to: Date, now: Date): string {
  const left = Math.max(0, Math.floor((to.getTime() - now.getTime()) / 1000));
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(Math.floor(left / 3600))}:${p(Math.floor((left % 3600) / 60))}:${p(left % 60)}`;
}

export function dayLabel(d: Date): string {
  return `${d.getMonth() + 1}/${d.getDate()}(${"일월화수목금토"[d.getDay()]})`;
}

export function ago(at: Date, now: Date): string {
  const s = Math.max(0, Math.floor((now.getTime() - at.getTime()) / 1000));
  if (s < 5) return "방금";
  if (s < 60) return `${s}초 전`;
  return `${Math.floor(s / 60)}분 전`;
}
