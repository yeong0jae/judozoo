import type { ReactNode } from "react";

/** 레일이 64px이라 라벨은 2자로 맞춘다. 전체 이름은 title 속성으로 붙인다. */
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
    label: "후보",
    full: "주도주 후보 조회",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" {...s}>
        <path d="M4 6h10M4 12h16M4 18h7" />
      </svg>
    ),
  },
  {
    to: "/signal-log",
    label: "로그",
    full: "주도주 실시간 로그",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" {...s}>
        <path d="M3 12h4l3-7 4 14 3-7h4" />
      </svg>
    ),
  },
  {
    to: "/breakout-radar",
    label: "돌파",
    full: "주도주 돌파 현황",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" {...s}>
        <path d="M3 17l6-6 4 4 8-8" />
        <path d="M17 7h4v4" />
      </svg>
    ),
  },
  {
    to: "/market-analysis",
    label: "시황",
    full: "시황 분석",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" {...s}>
        <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />
      </svg>
    ),
  },
  {
    to: "/theme-calendar",
    label: "테마",
    full: "테마 캘린더",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" {...s}>
        <rect x="3" y="5" width="18" height="16" rx="2" />
        <path d="M3 10h18M8 3v4M16 3v4" />
      </svg>
    ),
  },
  {
    to: "/timeline",
    label: "마감",
    full: "일별 마감",
    icon: (
      <svg width="20" height="20" viewBox="0 0 24 24" {...s}>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3 2" />
      </svg>
    ),
  },
];
