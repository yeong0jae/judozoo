import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Link, Navigate, NavLink, Route, Routes } from "react-router-dom";
import CommandPage from "./pages/CommandPage";
import MonitoringPage from "./pages/MonitoringPage";
import ReportPage from "./pages/ReportPage";
import SystemStatusBadge from "./components/SystemStatusBadge";
import { ToastProvider } from "./components/toast/Toast";
import { StompMockProvider } from "./ws/stompMock";
import { NotificationProvider } from "./notifications/notifications";
import { SettingsProvider } from "./settings/settings";
import { mockSystemStatus } from "./mocks/data";
const navItem = "px-4 py-2 rounded-md text-sm font-medium transition-colors hover:bg-zinc-800";
const activeItem = "bg-zinc-800 text-white";
export default function App() {
    return (_jsx(SettingsProvider, { children: _jsx(StompMockProvider, { children: _jsx(NotificationProvider, { children: _jsx(ToastProvider, { children: _jsx(AppShell, {}) }) }) }) }));
}
function AppShell() {
    return (_jsxs("div", { className: "min-h-full flex flex-col", children: [_jsx("header", { className: "border-b border-zinc-800 bg-zinc-950", children: _jsxs("div", { className: "max-w-7xl mx-auto px-6 py-3 flex items-center justify-between", children: [_jsx(Link, { to: "/", className: "text-lg font-semibold text-white", children: "\uC8FC\uB3C4\uC8FC \uC790\uB3D9 \uD2B8\uB808\uC774\uB529" }), _jsxs("nav", { className: "flex items-center gap-1", children: [_jsx(NavLink, { to: "/command", className: ({ isActive }) => `${navItem} ${isActive ? activeItem : "text-zinc-400"}`, children: "\uB9E4\uB9E4 \uBA85\uB839" }), _jsx(NavLink, { to: "/monitoring", className: ({ isActive }) => `${navItem} ${isActive ? activeItem : "text-zinc-400"}`, children: "\uC9C4\uD589 \uC911 \uBAA8\uB2C8\uD130\uB9C1" }), _jsx(NavLink, { to: "/report", className: ({ isActive }) => `${navItem} ${isActive ? activeItem : "text-zinc-400"}`, children: "\uC2E4\uC801 \uC870\uD68C" })] }), _jsx(SystemStatusBadge, { status: mockSystemStatus })] }) }), _jsx("main", { className: "flex-1 max-w-7xl mx-auto w-full px-6 py-6", children: _jsxs(Routes, { children: [_jsx(Route, { path: "/", element: _jsx(Navigate, { to: "/monitoring", replace: true }) }), _jsx(Route, { path: "/command", element: _jsx(CommandPage, {}) }), _jsx(Route, { path: "/monitoring", element: _jsx(MonitoringPage, {}) }), _jsx(Route, { path: "/report", element: _jsx(ReportPage, {}) })] }) })] }));
}
