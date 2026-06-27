import { useState } from "react";
import { NavLink } from "react-router-dom";
import NotificationBell from "../header/NotificationBell";
import MarketStatusBadge from "../header/MarketStatusBadge";
import KospiIndexBadge from "../header/KospiIndexBadge";
import KosdaqIndexBadge from "../header/KosdaqIndexBadge";
import StompStatusBadge from "../header/StompStatusBadge";
import SettingsButton from "../header/SettingsButton";
import { useMarketStatus } from "../../api/queries";

const NAV = [
  { to: "/leading-stocks", label: "주도주 후보 조회" },
  { to: "/signal-log", label: "주도주 실시간 로그" },
  { to: "/breakout-radar", label: "주도주 돌파 현황" },
  { to: "/theme-calendar", label: "테마 캘린더" },
  // 당분간 숨김 (라우트/페이지는 유지, 필요 시 주석 해제)
  // { to: "/command", label: "매매 명령" },
  // { to: "/monitoring", label: "모니터링" },
  // { to: "/holdings", label: "보유 주식" },
  // { to: "/report", label: "실적" },
];

const navBase = "rounded-lg text-sm font-medium transition-colors";
const activeItem = "bg-white/[0.06] text-zinc-100";
const inactiveItem = "text-zinc-400 hover:text-zinc-100 hover:bg-white/[0.03]";

export default function Header() {
  const { data: status } = useMarketStatus();
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <header className="border-b border-white/[0.04] bg-zinc-950">
      <div className="max-w-[110rem] mx-auto px-4 sm:px-6 py-3 flex items-center gap-4 md:gap-6">
        {/* 데스크톱 네비 */}
        <nav className="hidden md:flex items-center gap-1">
          {NAV.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              className={({ isActive }) =>
                `${navBase} px-3 py-1.5 ${isActive ? activeItem : inactiveItem}`
              }
            >
              {n.label}
            </NavLink>
          ))}
        </nav>

        <div className="flex-1" />

        {/* 데스크톱 우측 클러스터 */}
        <div className="hidden md:flex items-center gap-4">
          <div className="flex items-center gap-1">
            <KospiIndexBadge />
            <KosdaqIndexBadge />
            <NotificationBell />
            {status && <MarketStatusBadge status={status} />}
            <StompStatusBadge />
            <SettingsButton />
          </div>
        </div>

        {/* 모바일: 핵심 배지 + 햄버거 */}
        <div className="flex md:hidden items-center gap-1">
          {status && <MarketStatusBadge status={status} />}
          <NotificationBell />
          <button
            type="button"
            onClick={() => setMenuOpen((o) => !o)}
            aria-label={menuOpen ? "메뉴 닫기" : "메뉴 열기"}
            aria-expanded={menuOpen}
            className="p-2 rounded-md text-zinc-300 hover:bg-white/[0.06]"
          >
            <svg
              width="20"
              height="20"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            >
              {menuOpen ? (
                <>
                  <line x1="6" y1="6" x2="18" y2="18" />
                  <line x1="6" y1="18" x2="18" y2="6" />
                </>
              ) : (
                <>
                  <line x1="3" y1="6" x2="21" y2="6" />
                  <line x1="3" y1="12" x2="21" y2="12" />
                  <line x1="3" y1="18" x2="21" y2="18" />
                </>
              )}
            </svg>
          </button>
        </div>
      </div>

      {/* 모바일 드로어 */}
      {menuOpen && (
        <div className="md:hidden border-t border-white/[0.04] px-4 py-3 space-y-3">
          <nav
            className="flex flex-col gap-1"
            onClick={() => setMenuOpen(false)}
          >
            {NAV.map((n) => (
              <NavLink
                key={n.to}
                to={n.to}
                className={({ isActive }) =>
                  `${navBase} px-3 py-2.5 ${isActive ? activeItem : inactiveItem}`
                }
              >
                {n.label}
              </NavLink>
            ))}
          </nav>
          <div className="flex items-center flex-wrap gap-2 pt-3 border-t border-white/[0.04]">
            <KospiIndexBadge />
            <KosdaqIndexBadge />
            <StompStatusBadge />
            <SettingsButton />
          </div>
        </div>
      )}
    </header>
  );
}
