import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Link, NavLink } from "react-router-dom";
import NotificationBell from "../header/NotificationBell";
import SystemStatusBadge from "../header/SystemStatusBadge";
import StompStatusBadge from "../header/StompStatusBadge";
import SettingsButton from "../header/SettingsButton";
import { mockSystemStatus } from "../../mocks/data";
const navItem = "px-3 py-1.5 rounded-md text-sm font-medium transition-colors hover:bg-zinc-800";
const activeItem = "bg-zinc-800 text-white";
const inactiveItem = "text-zinc-400";
export default function Header() {
    // Phase 5-B-2에서 useSystemStatus()로 교체.
    const status = mockSystemStatus;
    return (_jsx("header", { className: "border-b border-zinc-800 bg-zinc-950", children: _jsxs("div", { className: "max-w-7xl mx-auto px-6 py-3 flex items-center gap-6", children: [_jsx(Link, { to: "/", className: "text-base font-semibold text-white whitespace-nowrap", children: "AT \uC790\uB3D9\uB9E4\uB9E4" }), _jsxs("nav", { className: "flex items-center gap-1", children: [_jsx(NavLink, { to: "/command", className: ({ isActive }) => `${navItem} ${isActive ? activeItem : inactiveItem}`, children: "\uB9E4\uB9E4 \uBA85\uB839" }), _jsx(NavLink, { to: "/monitoring", className: ({ isActive }) => `${navItem} ${isActive ? activeItem : inactiveItem}`, children: "\uBAA8\uB2C8\uD130\uB9C1" }), _jsx(NavLink, { to: "/report", className: ({ isActive }) => `${navItem} ${isActive ? activeItem : inactiveItem}`, children: "\uC2E4\uC801" })] }), _jsx("div", { className: "flex-1" }), _jsxs("div", { className: "flex items-center gap-1", children: [_jsx(NotificationBell, {}), _jsx(SystemStatusBadge, { status: status }), _jsx(StompStatusBadge, {}), _jsx(SettingsButton, {})] })] }) }));
}
