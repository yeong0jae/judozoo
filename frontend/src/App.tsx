import { Navigate, Route, Routes } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import CommandPage from "./pages/CommandPage";
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
  queryClient.invalidateQueries({ queryKey: QK.systemStatus });
  queryClient.invalidateQueries({ queryKey: QK.accountBalance });
  queryClient.invalidateQueries({ queryKey: QK.activeCommands });
  queryClient.invalidateQueries({ queryKey: QK.todayClosed });
  queryClient.invalidateQueries({ queryKey: ["trading", "detail"] });
};

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <SettingsProvider>
        <StompProvider onReconnect={handleStompReconnect}>
          <NotificationProvider>
            <ToastProvider>
              <NotificationsBridge />
              <AppShell />
            </ToastProvider>
          </NotificationProvider>
        </StompProvider>
      </SettingsProvider>
    </QueryClientProvider>
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
    </div>
  );
}
