import { jsxs as _jsxs, jsx as _jsx } from "react/jsx-runtime";
import { useEffect, useState } from "react";
import { mockActiveCommands, mockCommandDetails, mockTodayClosed, } from "../mocks/data";
import { colorByPnL, formatKrw, formatPct, formatPrice, formatQty } from "../lib/format";
export default function MonitoringPage() {
    const [selectedId, setSelectedId] = useState(mockActiveCommands[0]?.commandId ?? null);
    const [showCancelDialog, setShowCancelDialog] = useState(false);
    const [toast, setToast] = useState(null);
    // 종료 토스트 데모: 페이지 진입 후 4초 뒤 NO_FILL 종료 토스트 시뮬레이션
    useEffect(() => {
        const t = setTimeout(() => {
            setToast("LG에너지솔루션 명령 종료 — NO_FILL");
            setTimeout(() => setToast(null), 4000);
        }, 4000);
        return () => clearTimeout(t);
    }, []);
    const selectedDetail = selectedId ? mockCommandDetails[selectedId] : null;
    return (_jsxs("div", { className: "space-y-6", children: [_jsxs("section", { children: [_jsxs("h2", { className: "text-lg font-semibold mb-3", children: ["\uD65C\uC131 \uBA85\uB839 (", mockActiveCommands.length, ")"] }), _jsx("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden", children: _jsxs("table", { className: "w-full", children: [_jsx("thead", { className: "bg-zinc-950 text-xs uppercase text-zinc-500", children: _jsxs("tr", { children: [_jsx("th", { className: "text-left px-4 py-3", children: "\uC885\uBAA9\uBA85" }), _jsx("th", { className: "text-left px-4 py-3", children: "\uC0C1\uD0DC" }), _jsx("th", { className: "text-right px-4 py-3", children: "\uC218\uC775\uB960" }), _jsx("th", { className: "text-right px-4 py-3", children: "\uD3C9\uAC00\uC190\uC775" }), _jsx("th", { className: "text-center px-4 py-3", children: "\uB9E4\uC218 \uD68C\uCC28" }), _jsx("th", { className: "text-right px-4 py-3", children: "\uBCF4\uC720 \uC218\uB7C9" })] }) }), _jsx("tbody", { className: "text-sm", children: mockActiveCommands.map((cmd) => (_jsxs("tr", { onClick: () => setSelectedId(cmd.commandId), className: `border-t border-zinc-800 cursor-pointer hover:bg-zinc-800/50 ${selectedId === cmd.commandId ? "bg-zinc-800/70" : ""}`, children: [_jsxs("td", { className: "px-4 py-3 font-medium", children: [cmd.stockName, _jsx("span", { className: "text-xs text-zinc-500 ml-2", children: cmd.stockCode })] }), _jsx("td", { className: "px-4 py-3", children: _jsx(StatusPill, { status: cmd.status }) }), _jsx("td", { className: `px-4 py-3 text-right font-medium ${colorByPnL(cmd.profitRate)}`, children: formatPct(cmd.profitRate) }), _jsx("td", { className: `px-4 py-3 text-right ${colorByPnL(cmd.profitAmount)}`, children: formatKrw(cmd.profitAmount) }), _jsxs("td", { className: "px-4 py-3 text-center text-zinc-400", children: [cmd.buyAttempt.completed, "/", cmd.buyAttempt.total] }), _jsx("td", { className: "px-4 py-3 text-right text-zinc-300", children: formatQty(cmd.holdingQty) })] }, cmd.commandId))) })] }) })] }), selectedDetail && (_jsxs("section", { children: [_jsxs("h2", { className: "text-lg font-semibold mb-3", children: ["\uC0C1\uC138 \u2014 ", selectedDetail.stockName] }), _jsx(DetailPanel, { detail: selectedDetail, onCancel: () => setShowCancelDialog(true) })] })), _jsxs("section", { children: [_jsx("h3", { className: "text-sm font-semibold text-zinc-400 mb-3", children: "\uC624\uB298 \uC885\uB8CC\uB41C \uBA85\uB839" }), mockTodayClosed.length === 0 ? (_jsx("p", { className: "text-sm text-zinc-600", children: "\uC5C6\uC74C" })) : (_jsx("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden", children: _jsxs("table", { className: "w-full text-sm", children: [_jsx("thead", { className: "bg-zinc-950 text-xs uppercase text-zinc-500", children: _jsxs("tr", { children: [_jsx("th", { className: "text-left px-4 py-2", children: "\uC885\uBAA9\uBA85" }), _jsx("th", { className: "text-left px-4 py-2", children: "\uCCAD\uC0B0 \uC0AC\uC720" }), _jsx("th", { className: "text-right px-4 py-2", children: "\uC218\uC775\uB960" })] }) }), _jsx("tbody", { children: mockTodayClosed.map((row) => (_jsxs("tr", { className: "border-t border-zinc-800", children: [_jsx("td", { className: "px-4 py-2", children: row.stockName }), _jsx("td", { className: "px-4 py-2", children: row.closeReason && _jsx(CloseReasonBadge, { reason: row.closeReason }) }), _jsx("td", { className: `px-4 py-2 text-right ${colorByPnL(row.profitRate)}`, children: row.profitRate === 0 ? "-" : formatPct(row.profitRate) })] }, row.commandId))) })] }) }))] }), toast && (_jsx("div", { className: "fixed bottom-6 right-6 bg-zinc-800 border border-zinc-700 px-4 py-3 rounded-lg shadow-lg text-sm text-zinc-200", children: toast })), showCancelDialog && selectedDetail && (_jsx(ConfirmDialog, { title: "\uB9E4\uB9E4 \uC0AC\uC774\uD074 \uCDE8\uC18C", message: `${selectedDetail.stockName} 명령을 즉시 청산합니다. 보유 ${formatQty(selectedDetail.holdingQty)}이 시장가로 매도됩니다. 계속할까요?`, onConfirm: () => setShowCancelDialog(false), onCancel: () => setShowCancelDialog(false) }))] }));
}
function DetailPanel({ detail, onCancel, }) {
    const showSignals = detail.status === "HOLDING";
    const showMidwayOnly = detail.status === "BUYING";
    return (_jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-6 grid grid-cols-1 md:grid-cols-3 gap-6", children: [_jsx("div", { className: "space-y-3", children: _jsxs(Section, { title: "\uD3EC\uC9C0\uC158", children: [_jsx(KV, { label: "\uD3C9\uADE0 \uB9E4\uC218\uAC00", value: `${formatPrice(detail.averageBuyPrice)}원` }), _jsx(KV, { label: "\uD604\uC7AC\uAC00", value: `${formatPrice(detail.currentPrice)}원` }), _jsx(KV, { label: "\uC218\uC775\uB960", value: formatPct(detail.profitRate), valueClass: colorByPnL(detail.profitRate) }), _jsx(KV, { label: "\uD3C9\uAC00\uC190\uC775", value: formatKrw(detail.profitAmount), valueClass: colorByPnL(detail.profitAmount) }), _jsx(KV, { label: "\uBCF4\uC720 \uC218\uB7C9", value: formatQty(detail.holdingQty) }), _jsx(KV, { label: "\uB204\uC801 \uB9E4\uC218", value: formatQty(detail.totalBoughtQty) })] }) }), _jsxs("div", { className: "space-y-3", children: [_jsxs(Section, { title: "\uB9E4\uC218 \uC9C4\uD589", children: [_jsx(KV, { label: "\uD68C\uCC28 \uC9C4\uD589\uB3C4", value: `${detail.buyAttempt.completed} / ${detail.buyAttempt.total}` }), _jsx(KV, { label: "1\uD68C \uB9E4\uC218 \uAE08\uC561", value: formatKrw(detail.perBuyAmount) }), _jsx(KV, { label: "\uCD94\uAC00 \uB9E4\uC218 \uAC04\uACA9", value: `${detail.buyIntervalMin}분` })] }), _jsxs(Section, { title: "\uC2DC\uADF8\uB110", muted: !showSignals && !showMidwayOnly, children: [showMidwayOnly && (_jsx("p", { className: "text-xs text-zinc-500", children: "\uB9E4\uC218 \uC9C4\uD589 \uC911 \u2014 \uC911\uB3C4 \uC775\uC808 \uC678 \uC2DC\uADF8\uB110\uC740 \uBE44\uD65C\uC131" })), _jsx(KV, { label: "\uBAA9\uD45C \uC775\uC808 \uB2E8\uACC4", value: "", extra: _jsxs("div", { className: "flex gap-1.5", children: [_jsx(Stage, { label: "2%", fired: detail.tpStages.fired2pct, active: showSignals }), _jsx(Stage, { label: "3%", fired: detail.tpStages.fired3pct, active: showSignals }), _jsx(Stage, { label: "5%", fired: detail.tpStages.fired5pct, active: showSignals })] }) }), _jsx(KV, { label: "\uBD84\uD560 \uB9E4\uB3C4 \uC9C4\uD589", value: `${detail.splitSellProgress.soldPct}% / 100%` }), _jsx(KV, { label: "\uBCF8\uC804 \uB9E4\uB3C4 \uBB34\uC7A5", value: detail.breakevenArmed ? "무장됨" : "대기", valueClass: detail.breakevenArmed ? "text-amber-400" : "text-zinc-500" }), _jsx(KV, { label: "\uCD94\uC138 \uAEBE\uC784 \uBB34\uC7A5", value: detail.trendBreakArmed ? "무장됨" : "대기", valueClass: detail.trendBreakArmed ? "text-amber-400" : "text-zinc-500" })] })] }), _jsxs("div", { className: "space-y-3", children: [_jsxs(Section, { title: "\uC124\uC815\uAC12", children: [_jsx(KV, { label: "\uBD84\uD560 \uB9E4\uB3C4 \uBE44\uC728", value: `${(detail.splitSellRatio * 100).toFixed(0)}%` }), _jsx(KV, { label: "\uC911\uB3C4 \uC775\uC808", value: `+${detail.midwayProfitPct}%` }), _jsx(KV, { label: "\uBCF8\uC804 \uB9E4\uB3C4", value: `+${detail.breakevenThresholdPct}%` }), _jsx(KV, { label: "\uC190\uC808", value: `${detail.stopLossPct}%` })] }), _jsx("button", { onClick: onCancel, className: "w-full mt-2 bg-rose-900/40 hover:bg-rose-900/60 border border-rose-800 text-rose-200 px-4 py-2 rounded-md text-sm font-medium transition-colors", children: "\uB9E4\uB9E4 \uC0AC\uC774\uD074 \uCDE8\uC18C" })] })] }));
}
function Section({ title, children, muted, tone, }) {
    const cls = muted
        ? "opacity-50"
        : tone === "danger"
            ? "border-rose-900/60 bg-rose-950/30"
            : "border-zinc-800 bg-zinc-950";
    return (_jsxs("div", { className: `border ${cls} rounded-md p-4`, children: [_jsx("div", { className: "text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-2", children: title }), _jsx("div", { className: "space-y-1.5", children: children })] }));
}
function KV({ label, value, valueClass, extra, small, }) {
    return (_jsxs("div", { className: "flex justify-between items-center text-sm", children: [_jsx("span", { className: "text-zinc-500", children: label }), extra ?? (_jsx("span", { className: `${valueClass ?? "text-zinc-100"} ${small ? "text-xs" : ""}`, children: value }))] }));
}
function Stage({ label, fired, active, }) {
    if (!active) {
        return (_jsx("span", { className: "px-2 py-0.5 rounded text-xs bg-zinc-800 text-zinc-600", children: label }));
    }
    return (_jsxs("span", { className: `px-2 py-0.5 rounded text-xs ${fired
            ? "bg-emerald-900/60 text-emerald-300 border border-emerald-800"
            : "bg-zinc-800 text-zinc-400 border border-zinc-700"}`, children: [label, " ", fired && "✓"] }));
}
function StatusPill({ status }) {
    const map = {
        INITIATED: { label: "INITIATED", cls: "bg-zinc-700 text-zinc-300" },
        BUYING: { label: "BUYING", cls: "bg-blue-900/60 text-blue-300" },
        HOLDING: { label: "HOLDING", cls: "bg-emerald-900/60 text-emerald-300" },
        LIQUIDATING: { label: "LIQUIDATING", cls: "bg-amber-900/60 text-amber-300" },
        CLOSED: { label: "CLOSED", cls: "bg-zinc-800 text-zinc-500" },
    };
    const { label, cls } = map[status];
    return (_jsx("span", { className: `px-2 py-0.5 rounded text-xs font-medium ${cls}`, children: label }));
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
function ConfirmDialog({ title, message, onConfirm, onCancel, }) {
    return (_jsx("div", { className: "fixed inset-0 bg-black/70 flex items-center justify-center z-50", children: _jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-6 max-w-md w-full", children: [_jsx("h3", { className: "text-lg font-semibold mb-3", children: title }), _jsx("p", { className: "text-sm text-zinc-300 mb-6", children: message }), _jsxs("div", { className: "flex justify-end gap-2", children: [_jsx("button", { onClick: onCancel, className: "px-4 py-2 rounded text-sm bg-zinc-800 hover:bg-zinc-700", children: "\uB3CC\uC544\uAC00\uAE30" }), _jsx("button", { onClick: onConfirm, className: "px-4 py-2 rounded text-sm bg-rose-700 hover:bg-rose-600 text-white font-medium", children: "\uCDE8\uC18C \uC9C4\uD589" })] })] }) }));
}
