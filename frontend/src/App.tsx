import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { AnimatePresence, motion } from "motion/react";
import { QueryClientProvider } from "@tanstack/react-query";
import LeadingStocksPage from "./pages/LeadingStocksPage";
import BreakoutRadarPage from "./pages/BreakoutRadarPage";
import SignalLogPage from "./pages/SignalLogPage";
import TimelinePage from "./pages/TimelinePage";
import ThemeCalendarPage from "./pages/ThemeCalendarPage";
import ClosingBetPage from "./pages/ClosingBetPage";
import Header from "./components/layout/Header";
import { ToastProvider } from "./components/toast/Toast";
import { ThemeProvider } from "./theme/theme";
import { useTabTitle } from "./hooks/useTabTitle";
import { queryClient } from "./api/queryClient";

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <ToastProvider>
          <AppShell />
        </ToastProvider>
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
              <Route path="/breakout-radar" element={<BreakoutRadarPage />} />
              <Route path="/market-analysis" element={<ClosingBetPage />} />
              <Route path="/signal-log" element={<SignalLogPage />} />
              <Route path="/theme-calendar" element={<ThemeCalendarPage />} />
              <Route path="/timeline" element={<TimelinePage />} />
            </Routes>
          </motion.div>
        </AnimatePresence>
      </main>
    </div>
  );
}
