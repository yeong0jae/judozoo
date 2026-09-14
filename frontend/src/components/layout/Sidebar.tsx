import { NavLink } from "react-router-dom";
import { NAV } from "./nav";
import Logo from "./Logo";

/** 좌측 고정 레일. 모바일에서는 숨기고 Header의 드로어가 대신한다. */
export default function Sidebar() {
  return (
    <aside className="hidden md:flex sticky top-0 h-dvh w-20 shrink-0 flex-col items-center border-r border-white/[0.04] bg-zinc-950">
      <NavLink
        to="/leading-stocks"
        className="flex h-14 w-full items-center justify-center text-zinc-100"
        title="judozoo"
      >
        <Logo />
      </NavLink>

      <nav className="flex w-full flex-col items-center gap-1 py-2">
        {NAV.map((n) => (
          <NavLink
            key={n.to}
            to={n.to}
            title={n.full}
            className={({ isActive }) =>
              `flex w-[4.5rem] flex-col items-center gap-1 rounded-lg py-2 text-[11px] transition-colors ${
                isActive
                  ? "bg-white/[0.06] text-zinc-100"
                  : "text-zinc-500 hover:bg-white/[0.03] hover:text-zinc-200"
              }`
            }
          >
            {n.icon}
            <span className="leading-none">{n.label}</span>
          </NavLink>
        ))}
      </nav>
    </aside>
  );
}
