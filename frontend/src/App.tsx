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

const navItem =
  "px-4 py-2 rounded-md text-sm font-medium transition-colors hover:bg-zinc-800";
const activeItem = "bg-zinc-800 text-white";

export default function App() {
  return (
    <SettingsProvider>
      <StompMockProvider>
        <NotificationProvider>
          <ToastProvider>
            <AppShell />
          </ToastProvider>
        </NotificationProvider>
      </StompMockProvider>
    </SettingsProvider>
  );
}

function AppShell() {
  return (
    <div className="min-h-full flex flex-col">
      <header className="border-b border-zinc-800 bg-zinc-950">
        <div className="max-w-7xl mx-auto px-6 py-3 flex items-center justify-between">
          <Link to="/" className="text-lg font-semibold text-white">
            주도주 자동 트레이딩
          </Link>
          <nav className="flex items-center gap-1">
            <NavLink
              to="/command"
              className={({ isActive }) =>
                `${navItem} ${isActive ? activeItem : "text-zinc-400"}`
              }
            >
              매매 명령
            </NavLink>
            <NavLink
              to="/monitoring"
              className={({ isActive }) =>
                `${navItem} ${isActive ? activeItem : "text-zinc-400"}`
              }
            >
              진행 중 모니터링
            </NavLink>
            <NavLink
              to="/report"
              className={({ isActive }) =>
                `${navItem} ${isActive ? activeItem : "text-zinc-400"}`
              }
            >
              실적 조회
            </NavLink>
          </nav>
          <SystemStatusBadge status={mockSystemStatus} />
        </div>
      </header>
      <main className="flex-1 max-w-7xl mx-auto w-full px-6 py-6">
        <Routes>
          <Route path="/" element={<Navigate to="/monitoring" replace />} />
          <Route path="/command" element={<CommandPage />} />
          <Route path="/monitoring" element={<MonitoringPage />} />
          <Route path="/report" element={<ReportPage />} />
        </Routes>
      </main>
    </div>
  );
}
