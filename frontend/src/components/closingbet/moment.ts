/**
 * 시간대가 화면을 정한다 — 복기 09:05~15:00 · 베팅 15:00~20:00 · 대기 20:00~08:00 · 결과 08:00~09:05.
 * 주말은 휴장. 공휴일은 아직 모른다(백엔드 연동 때 거래일 달력으로 바꾼다).
 */
export type Moment = "review" | "bet" | "night" | "result" | "holiday";

export const MOMENTS: Moment[] = ["review", "bet", "night", "result", "holiday"];

const at = (base: Date, h: number, m: number, addDays = 0) => {
  const d = new Date(base);
  d.setDate(d.getDate() + addDays);
  d.setHours(h, m, 0, 0);
  return d;
};

const isWeekend = (d: Date) => d.getDay() === 0 || d.getDay() === 6;

/** 다음 평일(오늘 제외) */
function nextWeekday(now: Date): Date {
  let d = at(now, 0, 0, 1);
  while (isWeekend(d)) d = at(d, 0, 0, 1);
  return d;
}

export function momentAt(now: Date): Moment {
  if (isWeekend(now)) return "holiday";
  const m = now.getHours() * 60 + now.getMinutes();
  if (m < 8 * 60) return "night";
  if (m < 9 * 60 + 5) return "result";
  if (m < 15 * 60) return "review";
  if (m < 20 * 60) return "bet";
  return "night";
}

/** 이 시간대가 끝나는(다음 시간대가 시작하는) 시각 */
export function momentEnd(moment: Moment, now: Date): Date {
  switch (moment) {
    case "review":
      return at(now, 15, 0);
    case "bet":
      return at(now, 20, 0);
    case "night":
      return now.getHours() < 8 ? at(now, 8, 0) : at(now, 8, 0, 1);
    case "result":
      return at(now, 9, 5);
    case "holiday":
      return at(nextWeekday(now), 15, 0);
  }
}

export function nextOpenLabel(now: Date): string {
  const d = nextWeekday(now);
  return `${d.getMonth() + 1}/${d.getDate()}(${"일월화수목금토"[d.getDay()]})`;
}

export function countdown(to: Date, now: Date): string {
  const left = Math.max(0, Math.floor((to.getTime() - now.getTime()) / 1000));
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(Math.floor(left / 3600))}:${p(Math.floor((left % 3600) / 60))}:${p(left % 60)}`;
}
