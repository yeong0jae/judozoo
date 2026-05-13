import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type {
  AccountBalance,
  DailyReport,
  DailyTrading,
  Holding,
  MarketStatus,
  StockPriceResult,
  StockSearchResult,
  TradingDetail,
  TradingSummary,
} from "../types";

export const QK = {
  marketStatus: ["market", "status"] as const,
  accountBalance: ["account", "balance"] as const,
  holdings: ["account", "holdings"] as const,
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
    refetchInterval: 10 * 60_000,
  });
}

export function useAccountBalance() {
  return useQuery({
    queryKey: QK.accountBalance,
    queryFn: () => apiFetch<AccountBalance>("/api/account/balance"),
  });
}

export function useHoldings() {
  return useQuery({
    queryKey: QK.holdings,
    queryFn: () => apiFetch<Holding[]>("/api/account/holdings"),
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

// 백엔드 search-stock-info는 종목코드 정확 일치 조회라 미완성 코드("0", "00"...)는 KIS 에러를 부른다.
// 6자리 코드가 입력됐을 때만 호출한다.
const STOCK_CODE_PATTERN = /^[A-Za-z0-9]{6}$/;
export const isStockCode = (q: string) => STOCK_CODE_PATTERN.test(q.trim());

export function useStockSearch(query: string) {
  const code = query.trim();
  return useQuery({
    queryKey: QK.stockSearch(code),
    queryFn: () =>
      apiFetch<StockSearchResult[]>(
        `/api/stocks/search?q=${encodeURIComponent(code)}`,
      ),
    enabled: isStockCode(code),
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
