import { jsx as _jsx, jsxs as _jsxs, Fragment as _Fragment } from "react/jsx-runtime";
import { useMemo, useState } from "react";
import { formatDateTime, formatDuration, formatKRW, formatPct, formatPrice, } from "../lib/format";
import CloseReasonBadge from "../components/common/CloseReasonBadge";
import EmptyState from "../components/common/EmptyState";
import ErrorState from "../components/common/ErrorState";
import Skeleton from "../components/common/Skeleton";
import ProfitText from "../components/common/ProfitText";
import DetailPanel from "../components/trading/DetailPanel";
import { useSettings } from "../settings/settings";
import { useCommandDetail, useDailyReport } from "../api/queries";
const today = () => new Date().toISOString().slice(0, 10);
export default function ReportPage() {
    const settings = useSettings();
    const [date, setDate] = useState(today());
    const [sortKey, setSortKey] = useState("closedAt");
    const [pnlFilter, setPnLFilter] = useState("all");
    const [reasonFilter, setReasonFilter] = useState(new Set());
    const [drillDownId, setDrillDownId] = useState(null);
    const reportQ = useDailyReport(date);
    const detailQ = useCommandDetail(drillDownId);
    const allRows = reportQ.data ?? [];
    const filteredRows = useMemo(() => {
        return allRows
            .filter((r) => {
            if (pnlFilter === "win")
                return r.netProfit !== null && r.netProfit > 0;
            if (pnlFilter === "loss")
                return r.netProfit !== null && r.netProfit < 0;
            return true;
        })
            .filter((r) => reasonFilter.size === 0 ||
            (r.closeReason && reasonFilter.has(r.closeReason)))
            .sort(sortFn(sortKey));
    }, [allRows, sortKey, pnlFilter, reasonFilter]);
    const summary = useMemo(() => computeSummary(allRows), [allRows]);
    const unclosedCount = allRows.filter((r) => r.closeReason === "UNCLOSED").length;
    const drillDown = detailQ.data ?? null;
    return (_jsxs("div", { className: "space-y-6", children: [_jsxs("div", { className: "flex items-center justify-between flex-wrap gap-3", children: [_jsx("h2", { className: "text-lg font-semibold", children: "\uC77C\uBCC4 \uC2E4\uC801" }), _jsx(DateNavigator, { date: date, onChange: setDate })] }), settings.emphasizeUnclosed && unclosedCount > 0 && (_jsx(UnclosedBanner, { count: unclosedCount })), reportQ.isLoading ? (_jsxs("div", { className: "grid grid-cols-1 md:grid-cols-3 gap-4", children: [_jsx(Skeleton, { className: "h-32" }), _jsx(Skeleton, { className: "h-32" }), _jsx(Skeleton, { className: "h-32" })] })) : reportQ.isError ? (_jsx(ErrorState, { onRetry: () => reportQ.refetch() })) : (_jsx(SummaryCards, { summary: summary })), _jsxs("section", { children: [_jsxs("div", { className: "flex items-center justify-between mb-3 flex-wrap gap-3", children: [_jsxs("h3", { className: "text-sm font-semibold text-zinc-400", children: ["\uAC70\uB798 \uB0B4\uC5ED (", filteredRows.length, filteredRows.length !== allRows.length && ` / ${allRows.length}`, ")"] }), _jsxs("div", { className: "flex items-center gap-3 flex-wrap", children: [_jsx(PnLFilterButtons, { value: pnlFilter, onChange: setPnLFilter }), _jsx(ReasonFilter, { value: reasonFilter, onChange: setReasonFilter, available: new Set(allRows
                                            .map((r) => r.closeReason)
                                            .filter((x) => !!x)) }), _jsx(SortDropdown, { value: sortKey, onChange: setSortKey })] })] }), reportQ.isLoading ? (_jsx(Skeleton, { className: "h-32 w-full" })) : filteredRows.length === 0 ? (_jsx(EmptyState, { icon: "\uD83D\uDCCA", message: allRows.length === 0
                            ? `${date} 일자 거래가 없습니다`
                            : "필터 조건에 맞는 거래가 없습니다" })) : (_jsx(ReportTable, { rows: filteredRows, emphasizeUnclosed: settings.emphasizeUnclosed, onSelect: setDrillDownId, selectedId: drillDownId }))] }), drillDownId && (_jsxs("section", { children: [_jsxs("div", { className: "flex items-center justify-between mb-3", children: [_jsxs("h3", { className: "text-sm font-semibold text-zinc-400", children: ["\uC0AC\uC774\uD074 \uC0C1\uC138 ", drillDown && `— ${drillDown.stockName}`] }), _jsx("button", { onClick: () => setDrillDownId(null), className: "text-xs text-zinc-500 hover:text-zinc-300", children: "\uB2EB\uAE30 \u00D7" })] }), detailQ.isLoading ? (_jsx(Skeleton, { className: "h-64 w-full" })) : detailQ.isError ? (_jsx(ErrorState, { onRetry: () => detailQ.refetch() })) : drillDown ? (_jsx(DetailPanel, { detail: drillDown, live: false })) : null] }))] }));
}
// ============================================================
// Date navigator (좌우 화살표 + 캘린더)
// ============================================================
function DateNavigator({ date, onChange, }) {
    const shift = (days) => {
        const d = new Date(date);
        d.setDate(d.getDate() + days);
        onChange(d.toISOString().slice(0, 10));
    };
    const isToday = date === today();
    return (_jsxs("div", { className: "flex items-center gap-2 text-sm", children: [_jsx("button", { onClick: () => shift(-1), className: "px-2 py-1 rounded text-zinc-400 hover:bg-zinc-800", "aria-label": "\uC774\uC804 \uB0A0\uC9DC", children: "\u25C0" }), _jsx("input", { type: "date", value: date, max: today(), onChange: (e) => onChange(e.target.value), className: "bg-zinc-900 border border-zinc-800 rounded px-2 py-1 text-sm text-zinc-200" }), _jsx("button", { onClick: () => shift(1), disabled: isToday, className: "px-2 py-1 rounded text-zinc-400 hover:bg-zinc-800 disabled:opacity-40", "aria-label": "\uB2E4\uC74C \uB0A0\uC9DC", children: "\u25B6" }), _jsx("button", { onClick: () => onChange(today()), disabled: isToday, className: "px-2 py-1 text-xs rounded text-zinc-400 hover:bg-zinc-800 disabled:opacity-40", children: "\uC624\uB298" })] }));
}
// ============================================================
// UNCLOSED banner
// ============================================================
function UnclosedBanner({ count }) {
    return (_jsxs("div", { className: "bg-rose-950/50 border border-rose-800/60 rounded-lg px-4 py-3 text-sm text-rose-200", children: ["\uD83D\uDEA8 UNCLOSED \uAC70\uB798\uAC00 ", count, "\uAC74 \uC788\uC2B5\uB2C8\uB2E4 \u2014 KIS HTS\uC5D0\uC11C \uC218\uB3D9 \uC815\uB9AC\uAC00 \uD544\uC694\uD569\uB2C8\uB2E4"] }));
}
function computeSummary(rows) {
    // 청산되지 않은 사이클은 netProfit이 null — 합계/승패 카운트에서 제외한다.
    const closed = rows.filter((r) => r.netProfit !== null);
    const totalNet = closed.reduce((s, r) => s + (r.netProfit ?? 0), 0);
    const totalFee = rows.reduce((s, r) => s + r.totalFee, 0);
    const totalTax = rows.reduce((s, r) => s + r.totalTax, 0);
    const winCount = closed.filter((r) => (r.netProfit ?? 0) > 0).length;
    const lossCount = closed.filter((r) => (r.netProfit ?? 0) < 0).length;
    const drawCount = closed.length - winCount - lossCount;
    const decisive = winCount + lossCount;
    const reasonCounts = new Map();
    for (const r of rows) {
        if (!r.closeReason)
            continue;
        reasonCounts.set(r.closeReason, (reasonCounts.get(r.closeReason) ?? 0) + 1);
    }
    const avgHoldMs = rows.length === 0
        ? 0
        : rows.reduce((s, r) => {
            if (!r.closedAt)
                return s;
            return (s +
                (new Date(r.closedAt).getTime() -
                    new Date(r.createdAt).getTime()));
        }, 0) / rows.length;
    return {
        totalNet,
        totalFee,
        totalTax,
        totalCount: rows.length,
        winCount,
        lossCount,
        drawCount,
        winRate: decisive === 0 ? 0 : winCount / decisive,
        reasonCounts,
        avgHoldMs,
    };
}
function SummaryCards({ summary }) {
    return (_jsxs("div", { className: "grid grid-cols-1 md:grid-cols-3 gap-4", children: [_jsxs(SummaryCard, { title: "\uC21C\uC218\uC775", children: [_jsx(ProfitText, { value: summary.totalNet, format: formatKRW, className: "text-2xl font-bold", zeroAsDash: true }), (summary.totalFee > 0 || summary.totalTax > 0) && (_jsxs("div", { className: "text-xs text-zinc-500 mt-2 space-y-0.5", children: [_jsxs("div", { children: ["\u21B3 \uC218\uC218\uB8CC \u2212", formatKRW(summary.totalFee)] }), _jsxs("div", { children: ["\u21B3 \uC138\uAE08 \u2212", formatKRW(summary.totalTax)] })] }))] }), _jsxs(SummaryCard, { title: "\uAC70\uB798 \uAC74\uC218", children: [_jsxs("div", { className: "text-2xl font-bold", children: [summary.totalCount, "\uAC74"] }), _jsxs("div", { className: "text-sm mt-1 flex gap-3", children: [_jsxs("span", { className: "text-emerald-400", children: ["\uC2B9 ", summary.winCount] }), _jsxs("span", { className: "text-zinc-400", children: ["\uBB34 ", summary.drawCount] }), _jsxs("span", { className: "text-rose-400", children: ["\uD328 ", summary.lossCount] })] }), _jsxs("p", { className: "text-xs text-zinc-500 mt-2", children: ["\uC2B9\uB960 ", (summary.winRate * 100).toFixed(0), "%", summary.totalCount > 0 && summary.avgHoldMs > 0 && (_jsxs(_Fragment, { children: [" · ", "\uD3C9\uADE0 \uBCF4\uC720", " ", formatDuration("1970-01-01T00:00:00", new Date(summary.avgHoldMs).toISOString())] }))] })] }), _jsx(SummaryCard, { title: "\uCCAD\uC0B0 \uC0AC\uC720 \uBD84\uD3EC", children: _jsx(ReasonDistribution, { counts: summary.reasonCounts }) })] }));
}
function SummaryCard({ title, children, }) {
    return (_jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-4", children: [_jsx("h3", { className: "text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3", children: title }), children] }));
}
function ReasonDistribution({ counts, }) {
    if (counts.size === 0) {
        return _jsx("div", { className: "text-sm text-zinc-500", children: "\uB370\uC774\uD130 \uC5C6\uC74C" });
    }
    const total = Array.from(counts.values()).reduce((s, n) => s + n, 0);
    const sorted = Array.from(counts.entries()).sort((a, b) => b[1] - a[1]);
    return (_jsx("div", { className: "space-y-1.5", children: sorted.map(([reason, count]) => (_jsxs("div", { className: "flex items-center gap-2 text-xs", children: [_jsx(CloseReasonBadge, { reason: reason, showLabel: false }), _jsx("span", { className: "w-8 text-right text-zinc-300", children: count }), _jsx("div", { className: "flex-1 bg-zinc-800 rounded-full h-1.5 overflow-hidden", children: _jsx("div", { className: "h-full bg-zinc-500", style: { width: `${(count / total) * 100}%` } }) }), _jsxs("span", { className: "w-10 text-right text-zinc-500", children: [Math.round((count / total) * 100), "%"] })] }, reason))) }));
}
// ============================================================
// Filters / sort
// ============================================================
function PnLFilterButtons({ value, onChange, }) {
    const opts = [
        { v: "all", label: "전체" },
        { v: "win", label: "승만" },
        { v: "loss", label: "패만" },
    ];
    return (_jsx("div", { className: "flex items-center gap-1 text-xs bg-zinc-900 border border-zinc-800 rounded p-0.5", children: opts.map((o) => (_jsx("button", { onClick: () => onChange(o.v), className: `px-2 py-1 rounded ${value === o.v
                ? "bg-zinc-700 text-zinc-100"
                : "text-zinc-400 hover:text-zinc-200"}`, children: o.label }, o.v))) }));
}
function ReasonFilter({ value, onChange, available, }) {
    const reasons = [
        "TAKE_PROFIT",
        "STOP_LOSS",
        "BREAKEVEN",
        "TREND_BREAK",
        "MARKET_CLOSE",
        "CANCELLED",
        "NO_FILL",
        "UNCLOSED",
    ];
    const visible = reasons.filter((r) => available.has(r));
    if (visible.length === 0)
        return null;
    return (_jsxs("div", { className: "flex items-center gap-1", children: [visible.map((r) => {
                const selected = value.has(r);
                return (_jsx("button", { onClick: () => {
                        const next = new Set(value);
                        if (selected)
                            next.delete(r);
                        else
                            next.add(r);
                        onChange(next);
                    }, className: `transition-opacity ${selected ? "opacity-100" : "opacity-40 hover:opacity-70"}`, title: selected ? "필터에서 제외" : "필터에 추가", children: _jsx(CloseReasonBadge, { reason: r, showLabel: false }) }, r));
            }), value.size > 0 && (_jsx("button", { onClick: () => onChange(new Set()), className: "text-xs text-zinc-500 hover:text-zinc-300 ml-1", children: "\uCD08\uAE30\uD654" }))] }));
}
function SortDropdown({ value, onChange, }) {
    return (_jsxs("div", { className: "flex items-center gap-2 text-xs", children: [_jsx("span", { className: "text-zinc-500", children: "\uC815\uB82C" }), _jsxs("select", { value: value, onChange: (e) => onChange(e.target.value), className: "bg-zinc-900 border border-zinc-800 rounded px-2 py-1 text-zinc-200", children: [_jsx("option", { value: "closedAt", children: "\uC885\uB8CC\uC2DC\uAC01" }), _jsx("option", { value: "profitRate", children: "\uC218\uC775\uB960" })] })] }));
}
function sortFn(key) {
    if (key === "profitRate")
        return (a, b) => {
            if (a.profitRate === null && b.profitRate === null)
                return 0;
            if (a.profitRate === null)
                return 1;
            if (b.profitRate === null)
                return -1;
            return b.profitRate - a.profitRate;
        };
    return (a, b) => (b.closedAt ?? "").localeCompare(a.closedAt ?? "");
}
// ============================================================
// Table
// ============================================================
function ReportTable({ rows, emphasizeUnclosed, onSelect, selectedId, }) {
    return (_jsx("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg overflow-x-auto", children: _jsxs("table", { className: "w-full text-sm", children: [_jsx("thead", { className: "bg-zinc-950 text-xs uppercase text-zinc-500", children: _jsxs("tr", { children: [_jsx("th", { className: "text-left px-4 py-3", children: "\uC885\uB8CC\uC2DC\uAC01" }), _jsx("th", { className: "text-left px-4 py-3", children: "\uC885\uBAA9" }), _jsx("th", { className: "text-left px-4 py-3", children: "\uBCF4\uC720\uC2DC\uAC04" }), _jsx("th", { className: "text-left px-4 py-3", children: "\uB9E4\uC218\u2192\uB9E4\uB3C4\uAC00" }), _jsx("th", { className: "text-right px-4 py-3", children: "\uC218\uC775\uB960" }), _jsx("th", { className: "text-right px-4 py-3", children: "\uC21C\uC218\uC775" }), _jsx("th", { className: "text-center px-4 py-3", children: "\uC0AC\uC720" })] }) }), _jsx("tbody", { children: rows.map((r) => {
                        const isAnomaly = emphasizeUnclosed &&
                            (r.closeReason === "UNCLOSED" || r.closeReason === "NO_FILL");
                        const bg = r.closeReason === "UNCLOSED"
                            ? "bg-rose-950/30"
                            : r.closeReason === "NO_FILL"
                                ? "bg-amber-950/20"
                                : "";
                        return (_jsxs("tr", { onClick: () => onSelect(r.cycleId), className: `border-t border-zinc-800 cursor-pointer hover:bg-zinc-800/40 ${selectedId === r.cycleId ? "bg-zinc-800/60" : ""} ${isAnomaly ? bg : ""}`, children: [_jsx("td", { className: "px-4 py-3 text-zinc-400 whitespace-nowrap", children: r.closedAt ? formatDateTime(r.closedAt) : "-" }), _jsxs("td", { className: "px-4 py-3 font-medium", children: [r.stockName, _jsx("span", { className: "text-xs text-zinc-500 ml-2", children: r.stockCode })] }), _jsx("td", { className: "px-4 py-3 text-zinc-400", children: r.closedAt
                                        ? formatDuration(r.createdAt, r.closedAt)
                                        : "-" }), _jsxs("td", { className: "px-4 py-3 text-zinc-300 text-xs whitespace-nowrap", children: [r.avgBuyPrice !== null
                                            ? formatPrice(r.avgBuyPrice)
                                            : "-", " → ", r.avgSellPrice !== null
                                            ? formatPrice(r.avgSellPrice)
                                            : "-"] }), _jsx("td", { className: "px-4 py-3 text-right", children: _jsx(ProfitText, { value: r.profitRate, format: formatPct, zeroAsDash: true }) }), _jsx("td", { className: "px-4 py-3 text-right", children: _jsx(ProfitText, { value: r.netProfit, format: formatKRW, zeroAsDash: true }) }), _jsx("td", { className: "px-4 py-3 text-center", children: r.closeReason && (_jsx(CloseReasonBadge, { reason: r.closeReason })) })] }, r.cycleId));
                    }) })] }) }));
}
