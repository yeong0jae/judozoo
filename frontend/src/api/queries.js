import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
export const QK = {
    systemStatus: ["system", "status"],
    accountBalance: ["account", "balance"],
    activeCommands: ["trading", "active"],
    todayClosed: ["trading", "today"],
    commandDetail: (id) => ["trading", "detail", id],
    stockSearch: (q) => ["stocks", "search", q],
    stockPrice: (code) => ["stocks", "price", code],
    dailyReport: (date) => ["reports", "daily", date],
};
export function useSystemStatus() {
    return useQuery({
        queryKey: QK.systemStatus,
        queryFn: () => apiFetch("/api/system/status"),
        refetchInterval: 30_000,
    });
}
export function useAccountBalance() {
    return useQuery({
        queryKey: QK.accountBalance,
        queryFn: () => apiFetch("/api/account/balance"),
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
export function useStockSearch(query) {
    return useQuery({
        queryKey: QK.stockSearch(query),
        queryFn: () => apiFetch(`/api/stocks/search?q=${encodeURIComponent(query)}`),
        enabled: query.trim().length > 0,
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
