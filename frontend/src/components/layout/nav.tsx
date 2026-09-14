import type { ReactNode } from "react";

/** 레일이 80px이라 라벨은 4자(+가운뎃점)까지. 전체 이름은 title 속성으로 붙인다. */
export type NavItem = { to: string; label: string; full: string; icon: ReactNode };

const s = {
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.8,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
};

export const NAV: NavItem[] = [
  {
    to: "/leading-stocks",
    label: "주도주",
    full: "주도주 필터",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" {...s}>
        <path d="M4 6h10M4 12h16M4 18h7" />
      </svg>
    ),
  },
  {
    to: "/signal-log",
    label: "시그널",
    full: "주도주 시그널",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" {...s}>
        <path d="M3 12h4l3-7 4 14 3-7h4" />
      </svg>
    ),
  },
  {
    to: "/breakout-radar",
    label: "지지·저항",
    full: "지지·저항",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" {...s}>
        <path d="M3 17l6-6 4 4 8-8" />
        <path d="M17 7h4v4" />
      </svg>
    ),
  },
  {
    to: "/market-analysis",
    label: "지수·수급",
    full: "지수·수급",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" {...s}>
        <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />
      </svg>
    ),
  },
];
