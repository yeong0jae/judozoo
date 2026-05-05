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
  useTabTitle();
  return (
    <div className="min-h-full flex flex-col">
      <Header />
      <StompDisconnectionBanner />
      <main className="flex-1 max-w-7xl mx-auto w-full px-6 py-6">
        <Routes>
          <Route path="/" element={<Navigate to="/monitoring" replace />} />
          <Route path="/command" element={<CommandPage />} />
          <Route path="/monitoring" element={<MonitoringPage />} />
          <Route path="/report" element={<ReportPage />} />
        </Routes>
      </main>
      {import.meta.env.DEV && <StompDebugPanel />}
    </div>
  );
}
