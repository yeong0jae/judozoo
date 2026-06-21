import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { AnimatePresence, motion } from "motion/react";
import { QueryClientProvider } from "@tanstack/react-query";
import CommandPage from "./pages/CommandPage";
import HoldingsPage from "./pages/HoldingsPage";
import LeadingStocksPage from "./pages/LeadingStocksPage";
import SignalBoardPage from "./pages/SignalBoardPage";
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
  const location = useLocation();
  return (
    <div className="min-h-full flex flex-col">
      <Header />
      <StompDisconnectionBanner />
      <main className="flex-1 max-w-[110rem] mx-auto w-full px-4 sm:px-6 py-4 sm:py-6">
        <AnimatePresence mode="wait">
          <motion.div
            key={location.pathname}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}
          >
            <Routes location={location}>
              <Route path="/" element={<Navigate to="/leading-stocks" replace />} />
              <Route path="/leading-stocks" element={<LeadingStocksPage />} />
              <Route path="/signals" element={<SignalBoardPage />} />
              <Route path="/breakout-radar" element={<BreakoutRadarPage />} />
              <Route path="/volume-spike" element={<VolumeSpikePage />} />
              <Route path="/theme-calendar" element={<ThemeCalendarPage />} />
              <Route path="/market-flow" element={<MarketFlowPage />} />
              <Route path="/command" element={<CommandPage />} />
              <Route path="/monitoring" element={<MonitoringPage />} />
              <Route path="/holdings" element={<HoldingsPage />} />
              <Route path="/report" element={<ReportPage />} />
            </Routes>
          </motion.div>
        </AnimatePresence>
      </main>
    </div>
  );
}
