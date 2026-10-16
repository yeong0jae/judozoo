import { useEffect, useState } from "react";

export type SessionTone = "open" | "pre" | "post";

export type MarketSession = {
  name: string;
  tone: SessionTone;
  from: number;
  to: number;
};

const hm = (h: number, m: number) => h * 60 + m;

const KR_SESSIONS: MarketSession[] = [
  { name: "국내 프리마켓", tone: "pre", from: hm(8, 0), to: hm(9, 0) },
  { name: "국내 정규장", tone: "open", from: hm(9, 0), to: hm(15, 40) },
  { name: "국내 애프터마켓", tone: "post", from: hm(15, 40), to: hm(20, 0) },
];

const etFormat = (now: Date, options: Intl.DateTimeFormatOptions) =>
  new Intl.DateTimeFormat("en-US", { timeZone: "America/New_York", ...options }).format(now);

const etOffsetHours = (now: Date) =>
  etFormat(now, { timeZoneName: "short" }).includes("EDT") ? 13 : 14;

function usSessions(now: Date): MarketSession[] {
  const offset = etOffsetHours(now) * 60;
  const kst = (h: number, m: number) => (hm(h, m) + offset) % (24 * 60);
  return [
    { name: "해외 프리마켓", tone: "pre", from: kst(4, 0), to: kst(9, 30) },
    { name: "해외 정규장", tone: "open", from: kst(9, 30), to: kst(16, 0) },
    { name: "해외 애프터마켓", tone: "post", from: kst(16, 0), to: kst(20, 0) },
  ];
}

export const isEtWeekend = (now: Date) => ["Sat", "Sun"].includes(etFormat(now, { weekday: "short" }));

const isKstWeekend = (now: Date) => now.getDay() === 0 || now.getDay() === 6;

const contains = (minutes: number, session: MarketSession) =>
  session.from <= session.to
    ? minutes >= session.from && minutes < session.to
    : minutes >= session.from || minutes < session.to;

function current(sessions: MarketSession[], now: Date): MarketSession | null {
  const minutes = now.getHours() * 60 + now.getMinutes();
  return sessions.find((s) => contains(minutes, s)) ?? null;
}

const pad = (n: number) => String(n).padStart(2, "0");

const clock = (m: number) => `${pad(Math.floor(m / 60) % 24)}:${pad(m % 60)}`;

export function formatRange({ from, to }: MarketSession): string {
  return `${clock(from)} ~ ${clock(to)}`;
}

export function overseasIsMain(now: Date, krHoliday?: boolean): boolean {
  const hour = now.getHours();
  return isKstWeekend(now) || !!krHoliday || hour >= 20 || hour < 8;
}

export function usOpenClock(now: Date): string {
  return clock(usSessions(now)[0].from);
}

export function formatClock(now: Date): string {
  return `${pad(now.getHours())}:${pad(now.getMinutes())}`;
}

export function useMarketSessions(): {
  kr: MarketSession | null;
  us: MarketSession | null;
  now: Date;
} {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 30_000);
    return () => clearInterval(id);
  }, []);

  return {
    kr: isKstWeekend(now) ? null : current(KR_SESSIONS, now),
    us: isEtWeekend(now) ? null : current(usSessions(now), now),
    now,
  };
}

function backToWeekday(d: Date): Date {
  while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() - 1);
  return d;
}

export function krTradingDay(now: Date, holiday: boolean, previousOpenDay?: string | null): Date {
  if (!holiday && now.getHours() * 60 + now.getMinutes() >= hm(9, 0)) return new Date(now);
  if (previousOpenDay) {
    const [y, m, d] = previousOpenDay.split("-").map(Number);
    return new Date(y, m - 1, d);
  }
  const day = new Date(now);
  day.setDate(day.getDate() - 1);
  return backToWeekday(day);
}

function etParts(now: Date) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "America/New_York",
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
    hourCycle: "h23",
  }).formatToParts(now);
  const at = (type: string) => Number(parts.find((p) => p.type === type)!.value);
  return {
    year: at("year"),
    month: at("month"),
    day: at("day"),
    minutes: at("hour") * 60 + at("minute"),
  };
}

export function usTradingDay(now: Date, holiday: boolean): Date {
  const et = etParts(now);
  const day = new Date(et.year, et.month - 1, et.day);
  if (holiday || et.minutes < hm(4, 0)) day.setDate(day.getDate() - 1);
  return backToWeekday(day);
}

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

export function formatTradingDay(day: Date): string {
  return `${day.getMonth() + 1}/${day.getDate()} (${WEEKDAYS[day.getDay()]})`;
}
