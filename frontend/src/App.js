import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Navigate, Route, Routes } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import CommandPage from "./pages/CommandPage";
import HoldingsPage from "./pages/HoldingsPage";
import MonitoringPage from "./pages/MonitoringPage";
import ReportPage from "./pages/ReportPage";
import Header from "./components/layout/Header";
import StompDisconnectionBanner from "./components/layout/StompDisconnectionBanner";
import { ToastProvider } from "./components/toast/Toast";
import { StompProvider } from "./ws/StompProvider";
import { NotificationProvider } from "./notifications/notifications";
import NotificationsBridge from "./notifications/NotificationsBridge";
import { SettingsProvider } from "./settings/settings";
import { useTabTitle } from "./hooks/useTabTitle";
import { queryClient } from "./api/queryClient";
import { QK } from "./api/queries";
const handleStompReconnect = () => {
    // 재연결 시 모든 query invalidate — 누락 데이터 보강.
    queryClient.invalidateQueries({ queryKey: QK.marketStatus });
    queryClient.invalidateQueries({ queryKey: QK.accountBalance });
    queryClient.invalidateQueries({ queryKey: QK.holdings });
    queryClient.invalidateQueries({ queryKey: QK.activeCommands });
    queryClient.invalidateQueries({ queryKey: QK.todayClosed });
    queryClient.invalidateQueries({ queryKey: ["trading", "detail"] });
};
export default function App() {
    return (_jsx(QueryClientProvider, { client: queryClient, children: _jsx(SettingsProvider, { children: _jsx(StompProvider, { onReconnect: handleStompReconnect, children: _jsx(NotificationProvider, { children: _jsxs(ToastProvider, { children: [_jsx(NotificationsBridge, {}), _jsx(AppShell, {})] }) }) }) }) }));
}
function AppShell() {
    useTabTitle();
    return (_jsxs("div", { className: "min-h-full flex flex-col", children: [_jsx(Header, {}), _jsx(StompDisconnectionBanner, {}), _jsx("main", { className: "flex-1 max-w-7xl mx-auto w-full px-6 py-6", children: _jsxs(Routes, { children: [_jsx(Route, { path: "/", element: _jsx(Navigate, { to: "/monitoring", replace: true }) }), _jsx(Route, { path: "/command", element: _jsx(CommandPage, {}) }), _jsx(Route, { path: "/monitoring", element: _jsx(MonitoringPage, {}) }), _jsx(Route, { path: "/holdings", element: _jsx(HoldingsPage, {}) }), _jsx(Route, { path: "/report", element: _jsx(ReportPage, {}) })] }) })] }));
}
