import type { ReactNode } from "react";

/** 레일이 80px이라 라벨은 4자(+가운뎃점)까지. 전체 이름은 title 속성으로 붙인다. */
export type NavItem = { to: string; label: string; full: string; icon: ReactNode };

/**
 * 아이콘은 로고(`mark.json`)와 같은 문법으로 그린다 — 24 그리드, 면으로 채우고,
 * **빨강은 조각 하나만**. 로고에서 점·몸통·갈고리 중 몸통 하나만 빨간 것과 같은 비중이다.
 * 나머지는 `currentColor`라 선택·호버 상태를 그대로 따라간다.
 */
const UP = "#f04452";

const line = {
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
};

const Icon = ({ children }: { children: ReactNode }) => (
  <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden>
    {children}
  </svg>
);

export const NAV: NavItem[] = [
  {
    to: "/leading-stocks",
    label: "주도주",
    full: "오늘의 주도주",
    // 후보 여럿 중 하나가 솟는다 — 주인공 막대가 빨갛다
    icon: (
      <Icon>
        <rect x="3.6" y="12.4" width="4" height="7" rx="2" fill="currentColor" />
        <rect x="10" y="7.6" width="4" height="11.8" rx="2" fill={UP} />
        <rect x="16.4" y="14.8" width="4" height="4.6" rx="2" fill="currentColor" />
        <circle cx="12" cy="3.8" r="1.9" fill="currentColor" />
      </Icon>
    ),
  },
  {
    to: "/signal-log",
    label: "시그널",
    full: "주도주 시그널",
    // 한 지점에서 신호가 퍼진다 — 발신점이 빨갛다
    icon: (
      <Icon>
        <circle cx="12" cy="12" r="3.1" fill={UP} />
        <path d="M6.6 6.6a7.6 7.6 0 0 0 0 10.8M17.4 17.4a7.6 7.6 0 0 0 0-10.8" {...line} />
      </Icon>
    ),
  },
  {
    to: "/breakout-radar",
    label: "눌림·돌파",
    full: "눌림·돌파",
    // 위아래 선 사이에 현재가가 놓인다 — 그 현재가가 빨갛다
    icon: (
      <Icon>
        <rect x="3.4" y="4.4" width="17.2" height="3" rx="1.5" fill="currentColor" />
        <rect x="3.4" y="16.6" width="17.2" height="3" rx="1.5" fill="currentColor" />
        <circle cx="12" cy="12" r="2.6" fill={UP} />
      </Icon>
    ),
  },
  {
    to: "/market-analysis",
    label: "지수·수급",
    full: "지수·수급",
    // 지수 아래 순매수가 쌓인다 — 가장 높은 막대가 빨갛다
    icon: (
      <Icon>
        <path d="M3.4 8.4l5-4.2 4.4 3.4 7.8-5" {...line} />
        <rect x="3.6" y="13.2" width="4" height="6.6" rx="1.6" fill="currentColor" />
        <rect x="10" y="10.6" width="4" height="9.2" rx="1.6" fill={UP} />
        <rect x="16.4" y="15.4" width="4" height="4.4" rx="1.6" fill="currentColor" />
        <circle cx="20.6" cy="3.2" r="1.7" fill="currentColor" />
      </Icon>
    ),
  },
];
