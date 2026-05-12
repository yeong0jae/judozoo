import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
export const QK = {
    marketStatus: ["market", "status"],
    accountBalance: ["account", "balance"],
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
// 백엔드 search-stock-info는 종목코드 정확 일치 조회라 미완성 코드("0", "00"...)는 KIS 에러를 부른다.
// 6자리 코드가 입력됐을 때만 호출한다.
const STOCK_CODE_PATTERN = /^[A-Za-z0-9]{6}$/;
export const isStockCode = (q) => STOCK_CODE_PATTERN.test(q.trim());
export function useStockSearch(query) {
    const code = query.trim();
    return useQuery({
        queryKey: QK.stockSearch(code),
        queryFn: () => apiFetch(`/api/stocks/search?q=${encodeURIComponent(code)}`),
        enabled: isStockCode(code),
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
