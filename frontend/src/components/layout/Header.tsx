import { useState } from "react";
import { NavLink } from "react-router-dom";
import SettingsButton from "../header/SettingsButton";
import AccountButton from "../header/AccountButton";
import InstagramIcon from "../common/InstagramIcon";
import { INSTAGRAM_URL } from "../../lib/instagram";
import { NAV } from "./nav";
import HeaderTicker from "./HeaderTicker";
import Wordmark from "./Wordmark";

export default function Header() {
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <header className="sticky top-0 z-20 border-b border-zinc-800 bg-zinc-950">
      <div className="flex h-14 items-center gap-2 px-4 sm:px-6">
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

        <NavLink to="/" className="md:hidden" title="judozoo">
          <Wordmark size={22} />
        </NavLink>

        <div className="hidden min-w-0 flex-1 md:flex">
          <HeaderTicker />
        </div>
        <div className="flex-1 md:hidden" />

        <div className="flex items-center gap-1 md:hidden">
          <a
            href={INSTAGRAM_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="flex h-10 w-10 items-center justify-center rounded-lg text-zinc-500 transition-colors hover:bg-zinc-850 hover:text-zinc-200"
            aria-label="인스타그램"
            title="인스타그램"
          >
            <InstagramIcon />
          </a>
          <AccountButton placement="bottom" />
          <SettingsButton />
        </div>
      </div>

      <div className="flex h-[34px] items-center border-t border-zinc-800 empty:hidden md:hidden">
        <HeaderTicker />
      </div>

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
