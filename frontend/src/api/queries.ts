import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type {
  AccountBalance,
  DailyReport,
  DailyTrading,
  MarketStatus,
  StockPriceResult,
  StockSearchResult,
  TradingDetail,
  TradingSummary,
} from "../types";

export const QK = {
  marketStatus: ["market", "status"] as const,
  accountBalance: ["account", "balance"] as const,
  activeCommands: ["trading", "active"] as const,
  todayClosed: ["trading", "today"] as const,
  commandDetail: (id: number) => ["trading", "detail", id] as const,
  stockSearch: (q: string) => ["stocks", "search", q] as const,
  stockPrice: (code: string) => ["stocks", "price", code] as const,
  dailyReport: (date: string) => ["reports", "daily", date] as const,
};

export function useMarketStatus() {
  return useQuery({
    queryKey: QK.marketStatus,
    queryFn: () => apiFetch<MarketStatus>("/api/market/status"),
    refetchInterval: 30_000,
  });
}

export function useAccountBalance() {
  return useQuery({
    queryKey: QK.accountBalance,
    queryFn: () => apiFetch<AccountBalance>("/api/account/balance"),
  });
}

export function useActiveCommands() {
  return useQuery({
    queryKey: QK.activeCommands,
    queryFn: () =>
      apiFetch<TradingSummary[]>("/api/trading?status=active"),
  });
}

export function useTodayClosed() {
  return useQuery({
    queryKey: QK.todayClosed,
    queryFn: () =>
      apiFetch<DailyTrading[]>("/api/trading?status=today"),
  });
}

export function useCommandDetail(id: number | null) {
  return useQuery({
    queryKey: id ? QK.commandDetail(id) : ["trading", "detail", "null"],
    queryFn: () => apiFetch<TradingDetail>(`/api/trading/${id}`),
    enabled: id !== null,
  });
}

export function useStockSearch(query: string) {
  return useQuery({
    queryKey: QK.stockSearch(query),
    queryFn: () =>
      apiFetch<StockSearchResult[]>(
        `/api/stocks/search?q=${encodeURIComponent(query)}`,
      ),
    enabled: query.trim().length > 0,
  });
}

export function useStockPrice(code: string | null) {
  return useQuery({
    queryKey: code ? QK.stockPrice(code) : ["stocks", "price", "null"],
    queryFn: () =>
      apiFetch<StockPriceResult>(`/api/stocks/${code}/price`),
    enabled: code !== null,
  });
}

export function useDailyReport(date: string) {
  return useQuery({
    queryKey: QK.dailyReport(date),
    queryFn: () =>
      apiFetch<DailyReport[]>(`/api/reports/daily?date=${date}`),
  });
}
