import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useState } from "react";
import { mockDailyReport } from "../mocks/data";
import { colorByPnL, formatKrw, formatPct } from "../lib/format";
const dates = Array.from(new Set(mockDailyReport.map((r) => (r.closedAt ?? r.createdAt).slice(0, 10))))
    .sort()
    .reverse();
export default function ReportPage() {
    const [date, setDate] = useState(dates[0]);
    const rows = mockDailyReport.filter((r) => (r.closedAt ?? r.createdAt).slice(0, 10) === date);
    const totals = rows.reduce((acc, r) => {
        acc.profit += r.profitAmount;
        acc.count += 1;
        return acc;
    }, { profit: 0, count: 0 });
    return (_jsxs("div", { className: "space-y-4", children: [_jsxs("div", { className: "flex items-center justify-between", children: [_jsx("h2", { className: "text-lg font-semibold", children: "\uC77C\uBCC4 \uC2E4\uC801" }), _jsx("select", { value: date, onChange: (e) => setDate(e.target.value), className: "bg-zinc-900 border border-zinc-800 rounded px-3 py-1.5 text-sm", children: dates.map((d) => (_jsx("option", { value: d, children: d }, d))) })] }), _jsx("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg overflow-x-auto", children: _jsxs("table", { className: "w-full text-sm", children: [_jsx("thead", { className: "bg-zinc-950 text-xs uppercase text-zinc-500", children: _jsxs("tr", { children: [_jsx("th", { className: "text-left px-4 py-3", children: "\uC885\uB8CC\uC2DC\uAC01" }), _jsx("th", { className: "text-left px-4 py-3", children: "\uC885\uBAA9\uBA85" }), _jsx("th", { className: "text-right px-4 py-3", children: "\uC218\uC775\uAE08" }), _jsx("th", { className: "text-right px-4 py-3", children: "\uC218\uC775\uB960" }), _jsx("th", { className: "text-center px-4 py-3", children: "\uCCAD\uC0B0 \uC0AC\uC720" })] }) }), _jsxs("tbody", { children: [rows.map((r) => {
                                    const isAnomaly = r.closeReason === "UNCLOSED" || r.closeReason === "NO_FILL";
                                    return (_jsxs("tr", { className: `border-t border-zinc-800 ${isAnomaly ? "bg-amber-950/20" : ""}`, children: [_jsx("td", { className: "px-4 py-3 text-zinc-400", children: r.closedAt?.slice(11, 19) ?? "-" }), _jsxs("td", { className: "px-4 py-3 font-medium", children: [r.stockName, _jsx("span", { className: "text-xs text-zinc-500 ml-2", children: r.stockCode })] }), _jsx("td", { className: `px-4 py-3 text-right font-medium ${colorByPnL(r.profitAmount)}`, children: r.profitAmount === 0 ? "-" : formatKrw(r.profitAmount) }), _jsx("td", { className: `px-4 py-3 text-right ${colorByPnL(r.profitRate)}`, children: r.profitRate === 0 ? "-" : formatPct(r.profitRate) }), _jsx("td", { className: "px-4 py-3 text-center", children: r.closeReason && _jsx(CloseReasonBadge, { reason: r.closeReason }) })] }, r.commandId));
                                }), rows.length === 0 && (_jsx("tr", { children: _jsx("td", { colSpan: 5, className: "px-4 py-8 text-center text-zinc-600", children: "\uD574\uB2F9 \uC77C\uC790\uC758 \uC2E4\uC801\uC774 \uC5C6\uC2B5\uB2C8\uB2E4." }) }))] }), rows.length > 0 && (_jsx("tfoot", { className: "bg-zinc-950 text-sm font-medium border-t border-zinc-800", children: _jsxs("tr", { children: [_jsxs("td", { colSpan: 2, className: "px-4 py-3 text-zinc-400", children: ["\uD569\uACC4 (", totals.count, "\uAC74)"] }), _jsx("td", { className: `px-4 py-3 text-right ${colorByPnL(totals.profit)}`, children: formatKrw(totals.profit) }), _jsx("td", { colSpan: 2 })] }) }))] }) }), _jsx("p", { className: "text-xs text-zinc-500", children: "UNCLOSED / NO_FILL \uD589\uC740 \uC2DC\uC2A4\uD15C \uB2E4\uC6B4/\uC7AC\uC2DC\uC791 \uB610\uB294 3\uD68C \uB9E4\uC218 \uBBF8\uCCB4\uACB0\uB85C \uC778\uD55C \uBBF8\uCC98\uB9AC \uBA85\uB839\uC785\uB2C8\uB2E4. \uC218\uC218\uB8CC/\uC138\uAE08 \uBD84\uB9AC \uD45C\uC2DC\uB294 Phase 6\uC5D0\uC11C \uBC31\uC5D4\uB4DC \uC9D1\uACC4 \uC81C\uACF5 \uD6C4 \uD65C\uC131\uD654\uB429\uB2C8\uB2E4." })] }));
}
function CloseReasonBadge({ reason }) {
    const danger = ["STOP_LOSS", "UNCLOSED", "NO_FILL", "MARKET_CLOSE"].includes(reason);
    const success = ["TAKE_PROFIT", "TREND_BREAK"].includes(reason);
    const cls = danger
        ? "bg-rose-900/40 text-rose-300 border-rose-800"
        : success
            ? "bg-emerald-900/40 text-emerald-300 border-emerald-800"
            : "bg-zinc-800 text-zinc-300 border-zinc-700";
    return (_jsx("span", { className: `px-2 py-0.5 rounded text-xs border ${cls}`, children: reason }));
}
