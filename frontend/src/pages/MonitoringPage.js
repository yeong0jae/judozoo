import { jsxs as _jsxs, jsx as _jsx } from "react/jsx-runtime";
import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { mockActiveCommands, mockCommandDetails, mockTodayClosed, } from "../mocks/data";
import { formatDateTime, formatKRW, formatPct, formatPrice, formatQty, formatTime, } from "../lib/format";
import StatusPill from "../components/common/StatusPill";
import ProfitText from "../components/common/ProfitText";
import EmptyState from "../components/common/EmptyState";
import CloseReasonBadge from "../components/common/CloseReasonBadge";
import FlashOnChange from "../components/common/FlashOnChange";
import { useToast } from "../components/toast/Toast";
import { useNotifications } from "../notifications/notifications";
import { useSettings } from "../settings/settings";
const STATUS_ORDER = {
    LIQUIDATING: 0,
    HOLDING: 1,
    BUYING: 2,
    INITIATED: 3,
    CLOSED: 4,
};
export default function MonitoringPage() {
    // Phase 5-B-2: useActiveCommands() / useTodayClosed()로 교체.
    // 5-B-1에선 mock을 local state로 복사해 PRICE 시뮬레이션 가능하도록 만든다.
    const [commands, setCommands] = useState(mockActiveCommands);
    const [selectedId, setSelectedId] = useState(commands[0]?.commandId ?? null);
    const [showCancelDialog, setShowCancelDialog] = useState(false);
    const [sortKey, setSortKey] = useState("profit");
    const [reasonFilter, setReasonFilter] = useState(new Set());
    const toast = useToast();
    const notifications = useNotifications();
    const settings = useSettings();
    const todayRowRefs = useRef({});
    // === mock PRICE 시뮬레이터 — 3초마다 임의 행 수익률을 ±0.005 nudge ===
    useEffect(() => {
        if (commands.length === 0)
            return;
        const id = setInterval(() => {
            setCommands((prev) => prev.map((c, i) => {
                if (i !== Math.floor(Math.random() * prev.length))
                    return c;
                const delta = (Math.random() - 0.5) * 0.01;
                const newRate = Math.max(-0.1, Math.min(0.1, c.profitRate + delta));
                const newPrice = Math.round(c.averageBuyPrice * (1 + newRate));
                const newAmount = Math.round((newPrice - c.averageBuyPrice) * c.holdingQty);
                return {
                    ...c,
                    currentPrice: newPrice,
                    profitRate: newRate,
                    profitAmount: newAmount,
                };
            }));
        }, 3000);
        return () => clearInterval(id);
    }, [commands.length]);
    // === 종료 토스트 데모 + NotificationProvider 연동 ===
    useEffect(() => {
        const t = setTimeout(() => {
            const closed = mockTodayClosed[0];
            if (!closed?.closeReason)
                return;
            notifications.add({
                ts: new Date().toISOString(),
                commandId: closed.commandId,
                closeReason: closed.closeReason,
                stockName: closed.stockName,
                stockCode: closed.stockCode,
            });
            toast.show({
                message: `${closed.stockName} 명령이 종료되었습니다`,
                closeReason: closed.closeReason,
                action: {
                    label: "종료 행으로 이동",
                    onClick: () => {
                        const row = todayRowRefs.current[closed.commandId];
                        row?.scrollIntoView({ behavior: "smooth", block: "center" });
                        row?.classList.add("bg-amber-900/40");
                        setTimeout(() => row?.classList.remove("bg-amber-900/40"), 1500);
                    },
                },
            });
        }, 4000);
        return () => clearTimeout(t);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    const sortedCommands = useMemo(() => [...commands].sort(sortFn(sortKey)), [commands, sortKey]);
    const filteredTodayClosed = useMemo(() => {
        if (reasonFilter.size === 0)
            return mockTodayClosed;
        return mockTodayClosed.filter((r) => r.closeReason && reasonFilter.has(r.closeReason));
    }, [reasonFilter]);
    const selectedDetail = selectedId ? mockCommandDetails[selectedId] : null;
    return (_jsxs("div", { className: "space-y-6", children: [_jsxs("section", { children: [_jsxs("div", { className: "flex items-center justify-between mb-3", children: [_jsxs("h2", { className: "text-lg font-semibold", children: ["\uD65C\uC131 \uBA85\uB839 (", commands.length, ")"] }), commands.length > 0 && (_jsx(SortDropdown, { value: sortKey, onChange: setSortKey }))] }), commands.length === 0 ? (_jsx(EmptyState, { message: "\uD65C\uC131 \uB9E4\uB9E4 \uBA85\uB839\uC774 \uC5C6\uC2B5\uB2C8\uB2E4", action: _jsx(Link, { to: "/command", className: "px-4 py-2 rounded bg-emerald-700 hover:bg-emerald-600 text-sm text-white", children: "\uB9E4\uB9E4 \uBA85\uB839 \uC791\uC131\uD558\uAE30 \u2192" }) })) : (_jsx("div", { className: "space-y-2", children: sortedCommands.map((cmd) => (_jsx(ActiveRow, { cmd: cmd, selected: selectedId === cmd.commandId, onSelect: () => setSelectedId(cmd.commandId) }, cmd.commandId))) }))] }), selectedDetail && (_jsxs("section", { children: [_jsxs("h2", { className: "text-lg font-semibold mb-3", children: ["\uC0C1\uC138 \u2014 ", selectedDetail.stockName] }), _jsx(DetailPanel, { detail: selectedDetail, onCancel: () => setShowCancelDialog(true) })] })), _jsxs("section", { children: [_jsxs("div", { className: "flex items-center justify-between mb-3", children: [_jsxs("h3", { className: "text-sm font-semibold text-zinc-400", children: ["\uC624\uB298 \uC885\uB8CC\uB41C \uBA85\uB839 (", filteredTodayClosed.length, reasonFilter.size > 0 && ` / ${mockTodayClosed.length}`, ")"] }), mockTodayClosed.length > 0 && (_jsx(ReasonFilter, { value: reasonFilter, onChange: setReasonFilter, available: new Set(mockTodayClosed
                                    .map((r) => r.closeReason)
                                    .filter((x) => !!x)) }))] }), filteredTodayClosed.length === 0 ? (_jsx(EmptyState, { icon: "\uD83D\uDCED", message: reasonFilter.size > 0
                            ? "필터 조건에 맞는 종료 항목이 없습니다"
                            : "오늘 종료된 명령이 없습니다" })) : (_jsx(TodayClosedTable, { rows: filteredTodayClosed, emphasizeUnclosed: settings.emphasizeUnclosed, rowRefs: todayRowRefs }))] }), showCancelDialog && selectedDetail && (_jsx(ConfirmDialog, { title: "\uB9E4\uB9E4 \uC0AC\uC774\uD074 \uCDE8\uC18C", message: `${selectedDetail.stockName} 명령을 즉시 청산합니다. 보유 ${formatQty(selectedDetail.holdingQty)}이 시장가로 매도됩니다. 계속할까요?`, onConfirm: () => setShowCancelDialog(false), onCancel: () => setShowCancelDialog(false) }))] }));
}
function sortFn(key) {
    switch (key) {
        case "profit":
            return (a, b) => b.profitRate - a.profitRate;
        case "status":
            return (a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status];
        case "name":
            return (a, b) => a.stockName.localeCompare(b.stockName);
        case "buyProgress":
            return (a, b) => b.buyAttempt.completed - a.buyAttempt.completed;
    }
}
function SortDropdown({ value, onChange, }) {
    return (_jsxs("div", { className: "flex items-center gap-2 text-xs", children: [_jsx("span", { className: "text-zinc-500", children: "\uC815\uB82C" }), _jsxs("select", { value: value, onChange: (e) => onChange(e.target.value), className: "bg-zinc-900 border border-zinc-800 rounded px-2 py-1 text-zinc-200", children: [_jsx("option", { value: "profit", children: "\uC218\uC775\uB960" }), _jsx("option", { value: "status", children: "\uC0C1\uD0DC" }), _jsx("option", { value: "name", children: "\uC885\uBAA9\uBA85" }), _jsx("option", { value: "buyProgress", children: "\uB9E4\uC218 \uC9C4\uD589" })] })] }));
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
    return (_jsxs("div", { className: "flex items-center gap-1 flex-wrap", children: [visible.map((r) => {
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
function TodayClosedTable({ rows, emphasizeUnclosed, rowRefs, }) {
    return (_jsx("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden", children: _jsxs("table", { className: "w-full text-sm", children: [_jsx("thead", { className: "bg-zinc-950 text-xs uppercase text-zinc-500", children: _jsxs("tr", { children: [_jsx("th", { className: "text-left px-4 py-2", children: "\uCCAD\uC0B0 \uC0AC\uC720" }), _jsx("th", { className: "text-left px-4 py-2", children: "\uC885\uBAA9\uBA85" }), _jsx("th", { className: "text-left px-4 py-2", children: "\uC885\uB8CC\uC2DC\uAC01" }), _jsx("th", { className: "text-right px-4 py-2", children: "\uC218\uC775\uB960" }), _jsx("th", { className: "text-right px-4 py-2", children: "\uC218\uC775\uAE08" })] }) }), _jsx("tbody", { children: rows.map((row) => {
                        const isAnomaly = emphasizeUnclosed &&
                            (row.closeReason === "UNCLOSED" ||
                                row.closeReason === "NO_FILL");
                        const bg = row.closeReason === "UNCLOSED"
                            ? "bg-rose-950/30"
                            : row.closeReason === "NO_FILL"
                                ? "bg-amber-950/20"
                                : "";
                        return (_jsxs("tr", { ref: (el) => {
                                rowRefs.current[row.commandId] = el;
                            }, className: `border-t border-zinc-800 transition-colors ${isAnomaly ? bg : ""}`, children: [_jsx("td", { className: "px-4 py-2", children: row.closeReason && (_jsx(CloseReasonBadge, { reason: row.closeReason })) }), _jsxs("td", { className: "px-4 py-2 font-medium", children: [row.stockName, _jsx("span", { className: "text-xs text-zinc-500 ml-2", children: row.stockCode })] }), _jsx("td", { className: "px-4 py-2 text-zinc-400 text-xs", children: row.closedAt ? formatDateTime(row.closedAt) : "-" }), _jsx("td", { className: "px-4 py-2 text-right", children: _jsx(ProfitText, { value: row.profitRate, format: formatPct, zeroAsDash: true }) }), _jsx("td", { className: "px-4 py-2 text-right", children: _jsx(ProfitText, { value: row.profitAmount, format: formatKRW, zeroAsDash: true }) })] }, row.commandId));
                    }) })] }) }));
}
function DetailPanel({ detail, onCancel, live = true, }) {
    return (_jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden", children: [_jsx(SummaryHeader, { detail: detail, onCancel: onCancel, live: live }), _jsxs("div", { className: "p-6 space-y-6", children: [_jsx(BuyProgressSection, { detail: detail }), _jsx(SignalArmingBoard, { detail: detail }), _jsx(SplitSellSection, { detail: detail }), _jsx(OrderHistory, { orders: detail.orders }), _jsx(ExecutionHistory, { executions: detail.executions })] })] }));
}
function SummaryHeader({ detail, onCancel, live, }) {
    const isClosed = detail.status === "CLOSED";
    return (_jsx("div", { className: "bg-zinc-950 px-6 py-5 border-b border-zinc-800", children: _jsxs("div", { className: "flex items-start justify-between gap-4 flex-wrap", children: [_jsxs("div", { className: "min-w-0", children: [_jsxs("div", { className: "flex items-center gap-2 mb-2 flex-wrap", children: [_jsx(StatusPill, { status: detail.status }), isClosed && detail.closeReason && (_jsx(CloseReasonBadge, { reason: detail.closeReason })), _jsxs("h2", { className: "text-xl font-semibold", children: [detail.stockName, _jsx("span", { className: "text-sm text-zinc-500 ml-2", children: detail.stockCode })] })] }), _jsxs("div", { className: "flex gap-x-6 gap-y-1 text-sm text-zinc-400 flex-wrap", children: [_jsxs("span", { children: ["\uD3C9\uB2E8", " ", _jsxs("span", { className: "text-zinc-200", children: [formatPrice(detail.averageBuyPrice), "\uC6D0"] })] }), _jsxs("span", { children: ["\uD604\uC7AC", " ", _jsxs("span", { className: "text-zinc-200", children: [formatPrice(detail.currentPrice), "\uC6D0"] })] }), _jsxs("span", { children: ["\uBCF4\uC720", " ", _jsx("span", { className: "text-zinc-200", children: formatQty(detail.holdingQty) })] }), _jsxs("span", { children: ["\uB204\uC801 \uB9E4\uC218", " ", _jsx("span", { className: "text-zinc-200", children: formatQty(detail.totalBoughtQty) })] })] })] }), _jsxs("div", { className: "flex items-start gap-4", children: [_jsxs("div", { className: "text-right", children: [_jsx(ProfitText, { value: detail.profitRate, format: formatPct, className: "text-2xl font-bold block" }), _jsx(ProfitText, { value: detail.profitAmount, format: formatKRW, className: "text-sm" })] }), !isClosed && live && (_jsx("button", { onClick: onCancel, className: "bg-rose-900/40 hover:bg-rose-900/60 border border-rose-800 text-rose-200 px-3 py-1.5 rounded text-sm font-medium transition-colors whitespace-nowrap", children: "\uCDE8\uC18C" }))] })] }) }));
}
function BuyProgressSection({ detail }) {
    const lastBuy = detail.orders
        .filter((o) => o.side === "BUY")
        .sort((a, b) => b.submittedAt.localeCompare(a.submittedAt))[0];
    const remaining = detail.buyAttempt.total - detail.buyAttempt.completed;
    const nextBuyAt = lastBuy && remaining > 0
        ? new Date(new Date(lastBuy.submittedAt).getTime() +
            detail.buyIntervalMin * 60_000).toISOString()
        : null;
    return (_jsxs(Section, { title: "\uB9E4\uC218 \uC9C4\uD589", children: [_jsx("div", { className: "grid grid-cols-3 gap-3", children: Array.from({ length: detail.buyAttempt.total }).map((_, i) => {
                    const completed = i < detail.buyAttempt.completed;
                    const isNext = i === detail.buyAttempt.completed && nextBuyAt !== null;
                    return (_jsxs("div", { className: `border rounded p-3 ${completed
                            ? "border-emerald-700/60 bg-emerald-950/30"
                            : isNext
                                ? "border-amber-700/60 bg-amber-950/30"
                                : "border-zinc-800 bg-zinc-950"}`, children: [_jsxs("div", { className: "text-xs text-zinc-500 mb-1", children: ["\uD68C\uCC28 ", i + 1] }), _jsx("div", { className: "text-sm font-medium", children: completed ? "✓ 체결" : isNext ? "⏱ 다음 매수" : "대기" }), isNext && nextBuyAt && (_jsxs("div", { className: "text-xs text-amber-300 mt-1", children: ["~", formatTime(nextBuyAt)] }))] }, i));
                }) }), _jsxs("div", { className: "text-xs text-zinc-500 mt-2", children: ["1\uD68C \uB9E4\uC218 ", formatKRW(detail.perBuyAmount), " \u00B7 \uAC04\uACA9", " ", detail.buyIntervalMin, "\uBD84"] })] }));
}
function SignalArmingBoard({ detail }) {
    return (_jsx(Section, { title: "\uC2DC\uADF8\uB110 \uBB34\uC7A5", children: _jsxs("div", { className: "grid grid-cols-1 md:grid-cols-3 gap-3", children: [_jsx(ArmCard, { label: "\uC775\uC808 \uB2E8\uACC4", children: _jsxs("div", { className: "flex gap-1.5", children: [_jsx(Stage, { label: "2%", fired: detail.tpStages.fired2pct }), _jsx(Stage, { label: "3%", fired: detail.tpStages.fired3pct }), _jsx(Stage, { label: "5%", fired: detail.tpStages.fired5pct })] }) }), _jsx(ArmCard, { label: "\uBCF8\uC804 \uB9E4\uB3C4", children: _jsx(ArmStatus, { armed: detail.breakevenArmed, icon: "\uD83D\uDEE1" }) }), _jsx(ArmCard, { label: "\uCD94\uC138 \uAEBE\uC784", children: _jsx(ArmStatus, { armed: detail.trendBreakArmed, icon: "\uD83D\uDCC9" }) })] }) }));
}
function SplitSellSection({ detail }) {
    const pct = detail.splitSellProgress.soldPct;
    const totalSoldQty = detail.executions
        .filter((e) => detail.orders.find((o) => o.id === e.orderId)?.side === "SELL")
        .reduce((sum, e) => sum + e.executedQty, 0);
    return (_jsxs(Section, { title: "\uBD84\uD560 \uB9E4\uB3C4 \uC9C4\uD589", children: [_jsxs("div", { className: "flex items-center gap-3", children: [_jsx("div", { className: "flex-1 bg-zinc-800 rounded-full h-2 overflow-hidden", children: _jsx("div", { className: "h-full bg-emerald-600 transition-all", style: { width: `${pct}%` } }) }), _jsxs("span", { className: "text-sm text-zinc-300 whitespace-nowrap", children: [pct, "% (", formatQty(totalSoldQty), " / ", formatQty(detail.totalBoughtQty), ")"] })] }), _jsxs("div", { className: "text-xs text-zinc-500 mt-2", children: ["\uBD84\uD560 \uBE44\uC728 ", (detail.splitSellRatio * 100).toFixed(0), "% / \uD68C \u00B7 \uC911\uB3C4 \uC775\uC808 +", detail.midwayProfitPct, "% \u00B7 \uBCF8\uC804 +", detail.breakevenThresholdPct, "% \u00B7 \uC190\uC808 -", detail.stopLossPct, "%"] })] }));
}
function OrderHistory({ orders }) {
    if (orders.length === 0)
        return null;
    return (_jsx(Section, { title: `주문 이력 (${orders.length})`, children: _jsx("div", { className: "overflow-x-auto -mx-2", children: _jsxs("table", { className: "w-full text-xs", children: [_jsx("thead", { className: "text-zinc-500 uppercase", children: _jsxs("tr", { children: [_jsx("th", { className: "text-left px-2 py-2 font-medium", children: "\uC81C\uCD9C\uC2DC\uAC01" }), _jsx("th", { className: "text-left px-2 py-2 font-medium", children: "\uBC29\uD5A5" }), _jsx("th", { className: "text-left px-2 py-2 font-medium", children: "\uD2B8\uB9AC\uAC70" }), _jsx("th", { className: "text-right px-2 py-2 font-medium", children: "\uC8FC\uBB38" }), _jsx("th", { className: "text-right px-2 py-2 font-medium", children: "\uCCB4\uACB0" }), _jsx("th", { className: "text-left px-2 py-2 font-medium", children: "\uC0C1\uD0DC" }), _jsx("th", { className: "text-right px-2 py-2 font-medium", children: "\uC7AC\uC2DC\uB3C4" })] }) }), _jsx("tbody", { children: orders.map((o) => (_jsxs("tr", { className: "border-t border-zinc-800", children: [_jsx("td", { className: "px-2 py-2 text-zinc-400 whitespace-nowrap", children: formatDateTime(o.submittedAt) }), _jsx("td", { className: "px-2 py-2", children: _jsx(SideBadge, { side: o.side }) }), _jsx("td", { className: "px-2 py-2 text-zinc-400", children: o.trigger }), _jsx("td", { className: "px-2 py-2 text-right", children: o.orderQty }), _jsx("td", { className: "px-2 py-2 text-right", children: o.filledQty }), _jsx("td", { className: "px-2 py-2", children: _jsx(OrderStatusText, { status: o.status }) }), _jsx("td", { className: "px-2 py-2 text-right", children: o.retryCount > 0 ? (_jsxs("span", { className: o.retryCount >= 3
                                            ? "text-rose-300 font-medium"
                                            : "text-amber-300", title: o.lastError ?? undefined, children: [o.retryCount, "\uD68C"] })) : (_jsx("span", { className: "text-zinc-600", children: "-" })) })] }, o.id))) })] }) }) }));
}
function ExecutionHistory({ executions }) {
    if (executions.length === 0)
        return null;
    return (_jsx(Section, { title: `체결 이력 (${executions.length})`, children: _jsx("div", { className: "overflow-x-auto -mx-2", children: _jsxs("table", { className: "w-full text-xs", children: [_jsx("thead", { className: "text-zinc-500 uppercase", children: _jsxs("tr", { children: [_jsx("th", { className: "text-left px-2 py-2 font-medium", children: "\uCCB4\uACB0\uC2DC\uAC01" }), _jsx("th", { className: "text-right px-2 py-2 font-medium", children: "\uC218\uB7C9" }), _jsx("th", { className: "text-right px-2 py-2 font-medium", children: "\uAC00\uACA9" }), _jsx("th", { className: "text-right px-2 py-2 font-medium", children: "\uC218\uC218\uB8CC" }), _jsx("th", { className: "text-right px-2 py-2 font-medium", children: "\uC138\uAE08" })] }) }), _jsx("tbody", { children: executions.map((e, i) => (_jsxs("tr", { className: "border-t border-zinc-800", children: [_jsx("td", { className: "px-2 py-2 text-zinc-400 whitespace-nowrap", children: formatDateTime(e.executedAt) }), _jsx("td", { className: "px-2 py-2 text-right", children: e.executedQty }), _jsx("td", { className: "px-2 py-2 text-right", children: formatPrice(e.executedPrice) }), _jsx("td", { className: "px-2 py-2 text-right text-zinc-500", children: e.fee === 0 ? "-" : formatKRW(e.fee) }), _jsx("td", { className: "px-2 py-2 text-right text-zinc-500", children: e.tax === 0 ? "-" : formatKRW(e.tax) })] }, `${e.orderId}-${i}`))) })] }) }) }));
}
function Section({ title, children, }) {
    return (_jsxs("div", { children: [_jsx("h3", { className: "text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3", children: title }), children] }));
}
function ArmCard({ label, children, }) {
    return (_jsxs("div", { className: "border border-zinc-800 bg-zinc-950 rounded p-3", children: [_jsx("div", { className: "text-xs text-zinc-500 mb-2", children: label }), _jsx("div", { children: children })] }));
}
function ArmStatus({ armed, icon }) {
    return (_jsx("span", { className: armed ? "text-amber-300 font-medium" : "text-zinc-500", children: armed ? `${icon} 무장됨` : "⚪ 미무장" }));
}
function Stage({ label, fired }) {
    return (_jsxs("span", { className: `px-2 py-0.5 rounded text-xs border ${fired
            ? "bg-emerald-900/60 text-emerald-300 border-emerald-800"
            : "bg-zinc-800 text-zinc-500 border-zinc-700"}`, children: [label, " ", fired && "✓"] }));
}
function SideBadge({ side }) {
    return (_jsx("span", { className: `px-1.5 py-0.5 rounded text-[10px] font-medium ${side === "BUY"
            ? "bg-blue-900/60 text-blue-300"
            : "bg-rose-900/60 text-rose-300"}`, children: side }));
}
function OrderStatusText({ status }) {
    const cls = status === "FILLED"
        ? "text-emerald-300"
        : status === "CANCELLED"
            ? "text-zinc-500"
            : "text-amber-300";
    return _jsx("span", { className: cls, children: status });
}
function ActiveRow({ cmd, selected, onSelect, }) {
    return (_jsx("button", { onClick: onSelect, className: `w-full text-left rounded-lg border p-4 transition-colors ${selected
            ? "border-emerald-700 bg-zinc-800/60"
            : "border-zinc-800 bg-zinc-900 hover:bg-zinc-800/40"}`, children: _jsxs("div", { className: "grid grid-cols-[auto_1fr_auto_auto] items-center gap-4", children: [_jsx(StatusPill, { status: cmd.status }), _jsxs("div", { className: "min-w-0", children: [_jsxs("div", { className: "font-medium truncate", children: [cmd.stockName, _jsx("span", { className: "text-xs text-zinc-500 ml-2", children: cmd.stockCode })] }), _jsxs("div", { className: "text-xs text-zinc-500 mt-0.5", children: ["\uD3C9\uB2E8 ", formatPrice(cmd.averageBuyPrice), " \u2192 \uD604\uC7AC", " ", formatPrice(cmd.currentPrice)] })] }), _jsxs("div", { className: "text-right", children: [_jsx(FlashOnChange, { value: cmd.profitRate, children: _jsx(ProfitText, { value: cmd.profitRate, format: formatPct, className: "text-lg font-semibold" }) }), _jsx("div", { className: "text-xs", children: _jsx(ProfitText, { value: cmd.profitAmount, format: formatKRW }) })] }), _jsxs("div", { className: "flex flex-col items-end gap-1.5 min-w-[70px]", children: [_jsx(BuyAttemptDots, { completed: cmd.buyAttempt.completed, total: cmd.buyAttempt.total }), _jsxs("div", { className: "text-xs text-zinc-400 flex items-center gap-1", children: [formatQty(cmd.holdingQty), cmd.status === "LIQUIDATING" && (_jsx("span", { title: "\uCCAD\uC0B0 \uC911", "aria-label": "\uCCAD\uC0B0 \uC911", children: "\uD83D\uDD25" }))] })] })] }) }));
}
function BuyAttemptDots({ completed, total, }) {
    return (_jsx("div", { className: "flex gap-1", title: `매수 ${completed}/${total} 회차 완료`, "aria-label": `매수 ${completed}/${total} 회차`, children: Array.from({ length: total }).map((_, i) => (_jsx("span", { className: `w-2 h-2 rounded-full ${i < completed ? "bg-emerald-400" : "bg-zinc-700"}` }, i))) }));
}
function ConfirmDialog({ title, message, onConfirm, onCancel, }) {
    return (_jsx("div", { className: "fixed inset-0 bg-black/70 flex items-center justify-center z-50", children: _jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-6 max-w-md w-full", children: [_jsx("h3", { className: "text-lg font-semibold mb-3", children: title }), _jsx("p", { className: "text-sm text-zinc-300 mb-6", children: message }), _jsxs("div", { className: "flex justify-end gap-2", children: [_jsx("button", { onClick: onCancel, className: "px-4 py-2 rounded text-sm bg-zinc-800 hover:bg-zinc-700", children: "\uB3CC\uC544\uAC00\uAE30" }), _jsx("button", { onClick: onConfirm, className: "px-4 py-2 rounded text-sm bg-rose-700 hover:bg-rose-600 text-white font-medium", children: "\uCDE8\uC18C \uC9C4\uD589" })] })] }) }));
}
