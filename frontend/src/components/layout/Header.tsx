import { useState } from "react";
import { NavLink } from "react-router-dom";
import SettingsButton from "../header/SettingsButton";
import AccountButton from "../header/AccountButton";
import FeedbackButton from "../header/FeedbackButton";
import { NAV } from "./nav";
import HeaderTicker from "./HeaderTicker";
import Wordmark from "./Wordmark";

/**
 * 슬림 상단바. 네비게이션·계정·설정은 좌측 레일(Sidebar)이 맡는다.
 * 모바일에서는 레일이 숨으므로 여기 햄버거가 드로어로 네비를 제공한다.
 *
 * 시세 티커는 폭이 있는 데스크톱에서만 상단바 안에 들어간다. 모바일은
 * 자리가 없어 한 줄 아래 띠로 내린다. 띠도 이 헤더 안이라 sticky를 함께 타고,
 * 어느 화면을 보고 있든 시세가 계속 보인다.
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
        <NavLink to="/" className="md:hidden" title="judozoo">
          <Wordmark size={22} />
        </NavLink>

        {/* 모바일은 햄버거·워드마크·계정·설정이 이미 차지해 자리가 없다 */}
        <div className="hidden min-w-0 flex-1 md:flex">
          <HeaderTicker />
        </div>
        <div className="flex-1 md:hidden" />

        {/* 데스크톱은 좌측 레일 하단이 맡는다 — 레일이 숨는 모바일에서만 보여준다 */}
        <div className="flex items-center gap-1 md:hidden">
          <AccountButton placement="bottom" />
          <FeedbackButton placement="bottom" />
          <SettingsButton />
        </div>
      </div>

      {/* 모바일 시세 띠 — 상단바에 자리가 없어 한 줄 아래로 내린다.
          티커가 빈 목록이면 아무것도 렌더하지 않는다 — empty:hidden으로 띠까지 같이 접는다 */}
      <div className="flex h-[34px] items-center border-t border-zinc-800 empty:hidden md:hidden">
        <HeaderTicker />
      </div>

      {/* 모바일 드로어 */}
      {menuOpen && (
        <nav className="md:hidden border-t border-zinc-800 px-3 pb-3 pt-2">
          {NAV.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              end={n.end}
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
