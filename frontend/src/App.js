import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Navigate, Route, Routes } from "react-router-dom";
import CommandPage from "./pages/CommandPage";
import MonitoringPage from "./pages/MonitoringPage";
import ReportPage from "./pages/ReportPage";
import Header from "./components/layout/Header";
import StompDisconnectionBanner from "./components/layout/StompDisconnectionBanner";
import StompDebugPanel from "./components/dev/StompDebugPanel";
import { ToastProvider } from "./components/toast/Toast";
import { StompMockProvider } from "./ws/stompMock";
import { NotificationProvider } from "./notifications/notifications";
import { SettingsProvider } from "./settings/settings";
import { useTabTitle } from "./hooks/useTabTitle";
export default function App() {
    return (_jsx(SettingsProvider, { children: _jsx(StompMockProvider, { children: _jsx(NotificationProvider, { children: _jsx(ToastProvider, { children: _jsx(AppShell, {}) }) }) }) }));
}
function AppShell() {
    useTabTitle();
    return (_jsxs("div", { className: "min-h-full flex flex-col", children: [_jsx(Header, {}), _jsx(StompDisconnectionBanner, {}), _jsx("main", { className: "flex-1 max-w-7xl mx-auto w-full px-6 py-6", children: _jsxs(Routes, { children: [_jsx(Route, { path: "/", element: _jsx(Navigate, { to: "/monitoring", replace: true }) }), _jsx(Route, { path: "/command", element: _jsx(CommandPage, {}) }), _jsx(Route, { path: "/monitoring", element: _jsx(MonitoringPage, {}) }), _jsx(Route, { path: "/report", element: _jsx(ReportPage, {}) })] }) }), import.meta.env.DEV && _jsx(StompDebugPanel, {})] }));
}
