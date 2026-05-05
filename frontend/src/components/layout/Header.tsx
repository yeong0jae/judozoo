import { Link, NavLink } from "react-router-dom";
import NotificationBell from "../header/NotificationBell";
import SystemStatusBadge from "../header/SystemStatusBadge";
import StompStatusBadge from "../header/StompStatusBadge";
import SettingsButton from "../header/SettingsButton";
import { mockSystemStatus } from "../../mocks/data";

const navItem =
  "px-3 py-1.5 rounded-md text-sm font-medium transition-colors hover:bg-zinc-800";
const activeItem = "bg-zinc-800 text-white";
const inactiveItem = "text-zinc-400";

export default function Header() {
  // Phase 5-B-2에서 useSystemStatus()로 교체.
  const status = mockSystemStatus;
  return (
    <header className="border-b border-zinc-800 bg-zinc-950">
      <div className="max-w-7xl mx-auto px-6 py-3 flex items-center gap-6">
        <Link
          to="/"
          className="text-base font-semibold text-white whitespace-nowrap"
        >
          AT 자동매매
        </Link>
        <nav className="flex items-center gap-1">
          <NavLink
            to="/command"
            className={({ isActive }) =>
              `${navItem} ${isActive ? activeItem : inactiveItem}`
            }
          >
            매매 명령
          </NavLink>
          <NavLink
            to="/monitoring"
            className={({ isActive }) =>
              `${navItem} ${isActive ? activeItem : inactiveItem}`
            }
          >
            모니터링
          </NavLink>
          <NavLink
            to="/report"
            className={({ isActive }) =>
              `${navItem} ${isActive ? activeItem : inactiveItem}`
            }
          >
            실적
          </NavLink>
        </nav>
        <div className="flex-1" />
        <div className="flex items-center gap-1">
          <NotificationBell />
          <SystemStatusBadge status={status} />
          <StompStatusBadge />
          <SettingsButton />
        </div>
      </div>
    </header>
  );
}
