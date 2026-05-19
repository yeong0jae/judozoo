import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
export const QK = {
    marketStatus: ["market", "status"],
    accountBalance: ["account", "balance"],
    holdings: ["account", "holdings"],
    activeCommands: ["trading", "active"],
    todayClosed: ["trading", "today"],
    commandDetail: (id) => ["trading", "detail", id],
    stockSearch: (q) => ["stocks", "search", q],
    stockPrice: (code) => ["stocks", "price", code],
    dailyReport: (date) => ["reports", "daily", date],
};
export function useMarketStatus() {
    return useQuery({
        queryKey: QK.marketStatus,
        queryFn: () => apiFetch("/api/market/status"),
        refetchInterval: 10 * 60_000,
    });
}
export function useAccountBalance() {
    return useQuery({
        queryKey: QK.accountBalance,
        queryFn: () => apiFetch("/api/account/balance"),
    });
}
export function useHoldings() {
    return useQuery({
        queryKey: QK.holdings,
        queryFn: () => apiFetch("/api/account/holdings"),
    });
}
export function useActiveCommands() {
    return useQuery({
        queryKey: QK.activeCommands,
        queryFn: () => apiFetch("/api/trading?status=active"),
    });
}
export function useTodayClosed() {
    return useQuery({
        queryKey: QK.todayClosed,
        queryFn: () => apiFetch("/api/trading?status=today"),
    });
}
export function useCommandDetail(id) {
    return useQuery({
        queryKey: id ? QK.commandDetail(id) : ["trading", "detail", "null"],
        queryFn: () => apiFetch(`/api/trading/${id}`),
        enabled: id !== null,
    });
}
// 백엔드 검색은 로컬 종목 카탈로그(이름/코드 부분일치)라 2자 이상이면 질의한다.
export const STOCK_SEARCH_MIN_LEN = 2;
export function useStockSearch(query) {
    const q = query.trim();
    return useQuery({
        queryKey: QK.stockSearch(q),
        queryFn: () => apiFetch(`/api/stocks/search?q=${encodeURIComponent(q)}`),
        enabled: q.length >= STOCK_SEARCH_MIN_LEN,
    });
}
export function useStockPrice(code) {
    return useQuery({
        queryKey: code ? QK.stockPrice(code) : ["stocks", "price", "null"],
        queryFn: () => apiFetch(`/api/stocks/${code}/price`),
        enabled: code !== null,
    });
}
export function useDailyReport(date) {
    return useQuery({
        queryKey: QK.dailyReport(date),
        queryFn: () => apiFetch(`/api/reports/daily?date=${date}`),
    });
}
