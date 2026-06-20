import { Navigate, Route, Routes } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import CommandPage from "./pages/CommandPage";
import HoldingsPage from "./pages/HoldingsPage";
import LeadingStocksPage from "./pages/LeadingStocksPage";
import BreakoutRadarPage from "./pages/BreakoutRadarPage";
import VolumeSpikePage from "./pages/VolumeSpikePage";
import MarketFlowPage from "./pages/MarketFlowPage";
import MonitoringPage from "./pages/MonitoringPage";
import ReportPage from "./pages/ReportPage";
import ThemeCalendarPage from "./pages/ThemeCalendarPage";
import Header from "./components/layout/Header";
import StompDisconnectionBanner from "./components/layout/StompDisconnectionBanner";
import { ToastProvider } from "./components/toast/Toast";
import { StompProvider } from "./ws/StompProvider";
import { NotificationProvider } from "./notifications/notifications";
import NotificationsBridge from "./notifications/NotificationsBridge";
import { SettingsProvider } from "./settings/settings";
import { ThemeProvider } from "./theme/theme";
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
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
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
      </ThemeProvider>
    </QueryClientProvider>
  );
}

function AppShell() {
  useTabTitle();
  return (
    <div className="min-h-full flex flex-col">
      <Header />
      <StompDisconnectionBanner />
      <main className="flex-1 max-w-[110rem] mx-auto w-full px-4 sm:px-6 py-4 sm:py-6">
        <Routes>
          <Route path="/" element={<Navigate to="/leading-stocks" replace />} />
          <Route path="/leading-stocks" element={<LeadingStocksPage />} />
          <Route path="/breakout-radar" element={<BreakoutRadarPage />} />
          <Route path="/volume-spike" element={<VolumeSpikePage />} />
          <Route path="/theme-calendar" element={<ThemeCalendarPage />} />
          <Route path="/market-flow" element={<MarketFlowPage />} />
          <Route path="/command" element={<CommandPage />} />
          <Route path="/monitoring" element={<MonitoringPage />} />
          <Route path="/holdings" element={<HoldingsPage />} />
          <Route path="/report" element={<ReportPage />} />
        </Routes>
      </main>
    </div>
  );
}
