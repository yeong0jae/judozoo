import { useEffect, useState } from "react";

/**
 * 지금 어느 장이 도는지 — 국내·해외 각각 한 구간.
 *
 * 시각은 전부 상수라 서버에 물을 게 없다. 휴장 여부만 화면에서 합친다
 * (`/api/market/calendar/status`).
 */

export type SessionTone = "open" | "pre" | "post";

export type MarketSession = {
  name: string;
  tone: SessionTone;
  /** 자정 기준 분. `to < from`이면 자정을 넘는 구간이다(예: 22:30~05:00). */
  from: number;
  to: number;
};

const hm = (h: number, m: number) => h * 60 + m;

/**
 * 국내 — 백엔드 상수 그대로(KST).
 *
 * 정규장이 15:40에 끝나는 건 거래소 시간이 아니라 수급 표가 쓰는 경계다
 * (`market/application.py`의 `_CLOSE`). 실제 정규장은 15:30에 끝나지만
 * 여기서 15:30으로 자르면 15:30~15:40이 어느 구간도 아니게 되어
 * "장 닫힘"이 10분 떴다 사라진다. 같은 화면의 수급 표와 숫자를 맞춘다.
 */
const KR_SESSIONS: MarketSession[] = [
  { name: "국내 프리마켓", tone: "pre", from: hm(8, 0), to: hm(9, 0) },
  { name: "국내 정규장", tone: "open", from: hm(9, 0), to: hm(15, 40) },
  { name: "국내 애프터마켓", tone: "post", from: hm(15, 40), to: hm(20, 0) },
];

const etFormat = (now: Date, options: Intl.DateTimeFormatOptions) =>
  new Intl.DateTimeFormat("en-US", { timeZone: "America/New_York", ...options }).format(now);

/** 한국시간은 미 동부보다 서머타임이면 13시간, 표준시면 14시간 빠르다. */
const etOffsetHours = (now: Date) =>
  etFormat(now, { timeZoneName: "short" }).includes("EDT") ? 13 : 14;

/**
 * 해외 — 프리마켓과 정규장만 둔다.
 *
 * 애프터마켓·데이마켓은 빼는 게 맞다. 앱이 받는 건 나스닥·뉴욕·아멕스
 * 정규장 거래대금 순위뿐이라, 그 밖의 시간에 "열림"이라 써 봐야
 * 화면은 전날 종가에 멈춰 있다.
 */
function usSessions(now: Date): MarketSession[] {
  const offset = etOffsetHours(now) * 60;
  const kst = (h: number, m: number) => (hm(h, m) + offset) % (24 * 60);
  return [
    { name: "해외 프리마켓", tone: "pre", from: kst(4, 0), to: kst(9, 30) },
    { name: "해외 정규장", tone: "open", from: kst(9, 30), to: kst(16, 0) },
  ];
}

/** 미국장은 한국 새벽까지 이어진다 — 한국 요일로 세면 토요일 새벽(현지 금요일 장중)이 잘린다. */
const isEtWeekend = (now: Date) => ["Sat", "Sun"].includes(etFormat(now, { weekday: "short" }));

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

/** "09:00 ~ 15:40" */
export function formatRange({ from, to }: MarketSession): string {
  const clock = (m: number) => `${pad(Math.floor(m / 60) % 24)}:${pad(m % 60)}`;
  return `${clock(from)} ~ ${clock(to)}`;
}

/**
 * 국내·해외의 현재 구간. 장이 아니거나 주말이면 `null`.
 *
 * 주말은 휴장 API도 알려주지만, 응답이 오기 전에 토요일 낮을 "정규장"이라
 * 말해버리지 않도록 여기서도 본다. 공휴일은 API만 안다.
 */
export function useMarketSessions(): { kr: MarketSession | null; us: MarketSession | null } {
  const [now, setNow] = useState(() => new Date());

  // 경계를 넘으면 저절로 바뀌게 — 최대 1분 늦다
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(id);
  }, []);

  return {
    kr: isKstWeekend(now) ? null : current(KR_SESSIONS, now),
    us: isEtWeekend(now) ? null : current(usSessions(now), now),
  };
}
