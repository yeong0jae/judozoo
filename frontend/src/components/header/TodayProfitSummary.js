import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useTodayClosed } from "../../api/queries";
import { colorByPnL, formatKRW, formatPct } from "../../lib/format";
export default function TodayProfitSummary() {
    const { data, isLoading } = useTodayClosed();
    const rows = data ?? [];
    // UNCLOSED 등 손익이 확정되지 않은 사이클은 백엔드가 null로 보내므로 합계/카운트에서 제외.
    const realized = rows.filter((r) => r.profitAmount !== null && r.profitRate !== null);
    const totalProfit = realized.reduce((s, r) => s + r.profitAmount, 0);
    const totalBase = realized.reduce((s, r) => s +
        (r.profitAmount === 0
            ? 0
            : Math.abs(r.profitAmount / Math.max(r.profitRate, 0.0001))), 0);
    const aggregateRate = totalBase > 0 ? totalProfit / totalBase : 0;
    const count = realized.length;
    if (isLoading) {
        return (_jsx("div", { className: "text-xs text-zinc-600 hidden md:block", children: "\uC624\uB298 ..." }));
    }
    if (count === 0) {
        return (_jsx("div", { className: "text-xs text-zinc-500 hidden md:block", children: "\uC624\uB298 \uC885\uB8CC 0\uAC74" }));
    }
    return (_jsxs("div", { className: "text-xs hidden md:flex items-center gap-1.5", title: `오늘 종료 ${count}건의 합계 (수수료/세금 분리는 Phase 6)`, children: [_jsx("span", { className: "text-zinc-500", children: "\uC624\uB298" }), _jsx("span", { className: `font-medium ${colorByPnL(totalProfit)}`, children: formatKRW(totalProfit) }), _jsxs("span", { className: colorByPnL(aggregateRate), children: ["(", formatPct(aggregateRate), ")"] })] }));
}
