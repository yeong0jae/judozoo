import { useState } from "react";
import { NavLink } from "react-router-dom";
import KospiIndexBadge from "../header/KospiIndexBadge";
import KosdaqIndexBadge from "../header/KosdaqIndexBadge";
import SettingsButton from "../header/SettingsButton";
import AuthButton from "../header/AuthButton";
import { NAV } from "./nav";
import Wordmark from "./Wordmark";

/**
 * 슬림 상단바. 네비게이션은 좌측 레일(Sidebar)이 맡고 여기엔 지수·설정·로그인만 둔다.
 * 지수 배지는 가로로 넓어 64px 레일에 들어가지 않는다.
 * 모바일에서는 레일이 숨으므로 여기 햄버거가 드로어로 네비를 제공한다.
 */
export default function Header() {
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <header className="sticky top-0 z-20 border-b border-zinc-800 bg-zinc-950">
      <div className="flex h-14 items-center gap-2 px-4 sm:px-6">
        {/* 모바일: 햄버거 */}
        <button
          type="button"
          onClick={() => setMenuOpen((v) => !v)}
          aria-label="메뉴"
          className="md:hidden rounded-lg p-2 text-zinc-400 hover:bg-zinc-800 hover:text-zinc-100"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
            {menuOpen ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
          </svg>
        </button>

        {/* 모바일엔 좌측 레일이 없어 브랜드가 화면에서 사라진다 — 여기서만 보여준다 */}
        <NavLink to="/leading-stocks" className="md:hidden" title="judozoo">
          <Wordmark size={22} />
        </NavLink>

        <div className="flex-1" />

        <div className="flex items-center gap-1">
          <KospiIndexBadge />
          <KosdaqIndexBadge />
          <SettingsButton />
          <AuthButton />
        </div>
      </div>

      {/* 모바일 드로어 */}
      {menuOpen && (
        <nav className="md:hidden border-t border-zinc-800 px-3 pb-3 pt-2">
          {NAV.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              onClick={() => setMenuOpen(false)}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-colors ${
                  isActive
                    ? "bg-zinc-800 text-zinc-100"
                    : "text-zinc-400 hover:bg-zinc-850 hover:text-zinc-100"
                }`
              }
            >
              {n.icon}
              {n.full}
            </NavLink>
          ))}
        </nav>
      )}
    </header>
  );
}
