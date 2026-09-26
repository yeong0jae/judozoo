import type { ReactNode } from "react";

/** 레일이 80px이라 라벨은 4자(+가운뎃점)까지. 전체 이름은 title 속성으로 붙인다. */
export type NavItem = { to: string; label: string; full: string; icon: ReactNode };

/** 한 가지 색 선 아이콘 — `currentColor`라 선택·호버 상태를 그대로 따라간다. */
const line = {
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.8,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
};

const Icon = ({ children }: { children: ReactNode }) => (
  <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden {...line}>
    {children}
  </svg>
);

export const NAV: NavItem[] = [
  {
    to: "/leading-stocks",
    label: "주도주",
    full: "오늘의 주도주",
    icon: (
      <Icon>
        <path d="M4 6h10M4 12h16M4 18h7" />
      </Icon>
    ),
  },
  {
    to: "/leader-timeline",
    label: "타임라인",
    full: "주도주 타임라인",
    icon: (
      <Icon>
        <path d="M3 7h9M3 12h14M3 17h6" />
        <circle cx="16" cy="7" r="2" />
        <circle cx="20" cy="12" r="2" />
        <circle cx="12" cy="17" r="2" />
      </Icon>
    ),
  },
  {
    to: "/leader-calendar",
    label: "캘린더",
    full: "주도주 캘린더",
    icon: (
      <Icon>
        <rect x="3.5" y="5" width="17" height="15" rx="2.5" />
        <path d="M3.5 10h17M8 3v4M16 3v4" />
      </Icon>
    ),
  },
  {
    to: "/signal-log",
    label: "시그널",
    full: "주도주 시그널",
    icon: (
      <Icon>
        <path d="M3 12h4l3-7 4 14 3-7h4" />
      </Icon>
    ),
  },
  {
    to: "/breakout-radar",
    label: "눌림·돌파",
    full: "눌림·돌파",
    icon: (
      <Icon>
        <path d="M3 17l6-6 4 4 8-8" />
        <path d="M17 7h4v4" />
      </Icon>
    ),
  },
  {
    to: "/market-analysis",
    label: "지수·수급",
    full: "지수·수급",
    icon: (
      <Icon>
        <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />
      </Icon>
    ),
  },
];
