import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { formatDateTime, formatKRW, formatPct, formatPrice, formatQty, } from "../lib/format";
import StatusPill from "../components/common/StatusPill";
import ProfitText from "../components/common/ProfitText";
import EmptyState from "../components/common/EmptyState";
import ErrorState from "../components/common/ErrorState";
import Skeleton from "../components/common/Skeleton";
import CloseReasonBadge from "../components/common/CloseReasonBadge";
import FlashOnChange from "../components/common/FlashOnChange";
import DetailPanel from "../components/trading/DetailPanel";
import { useToast } from "../components/toast/Toast";
import { useSettings } from "../settings/settings";
import { QK, useActiveCommands, useCommandDetail, useTodayClosed, } from "../api/queries";
import { useCancelCommand } from "../api/mutations";
import { useStompSubscription } from "../ws/useStompSubscription";
import { ApiError } from "../api/client";
const STATUS_ORDER = {
    LIQUIDATING: 0,
    HOLDING: 1,
    BUYING: 2,
    INITIATED: 3,
    CLOSED: 4,
};
export default function MonitoringPage() {
    const qc = useQueryClient();
    const toast = useToast();
    const settings = useSettings();
    const activeQ = useActiveCommands();
    const todayQ = useTodayClosed();
    const commands = activeQ.data ?? [];
    const todayRows = todayQ.data ?? [];
    const [selectedId, setSelectedId] = useState(null);
    const [showCancelDialog, setShowCancelDialog] = useState(false);
    const [sortKey, setSortKey] = useState("profit");
    const [reasonFilter, setReasonFilter] = useState(new Set());
    const todayRowRefs = useRef({});
    const detailQ = useCommandDetail(selectedId);
    const cancelCommand = useCancelCommand();
    // /topic/trading/lifecycle — CREATED/CLOSED 시 active/today invalidate + 종료 행 스크롤 액션
    useStompSubscription("/topic/trading/lifecycle", (p) => {
        qc.invalidateQueries({ queryKey: QK.activeCommands });
        if (p.type === "CLOSED") {
            qc.invalidateQueries({ queryKey: QK.todayClosed });
            // NotificationsBridge가 toast/알림을 이미 띄움. 여기선 종료 행 스크롤 보강 토스트만 추가.
            toast.show({
                tone: "info",
                message: `명령 #${p.commandId} 종료 — 종료 행으로 이동`,
                duration: 5000,
                action: {
                    label: "이동",
                    onClick: () => {
                        const row = todayRowRefs.current[p.commandId];
                        row?.scrollIntoView({ behavior: "smooth", block: "center" });
                        row?.classList.add("bg-amber-900/40");
                        setTimeout(() => row?.classList.remove("bg-amber-900/40"), 1500);
                    },
                },
            });
        }
    });
    const sortedCommands = useMemo(() => [...commands].sort(sortFn(sortKey)), [commands, sortKey]);
    const filteredTodayClosed = useMemo(() => {
        if (reasonFilter.size === 0)
            return todayRows;
        return todayRows.filter((r) => r.closeReason && reasonFilter.has(r.closeReason));
    }, [todayRows, reasonFilter]);
    const selectedDetail = detailQ.data ?? null;
    const onCancelConfirm = async () => {
        if (!selectedId)
            return;
        try {
            await cancelCommand.mutateAsync(selectedId);
            setShowCancelDialog(false);
            toast.show({ tone: "success", message: "취소 요청됨" });
        }
        catch (e) {
            const code = e instanceof ApiError ? e.code : "SERVER_ERROR";
            toast.show({ tone: "error", message: `취소 실패 — ${code}` });
        }
    };
    return (_jsxs("div", { className: "space-y-6", children: [commands.map((c) => (_jsx(PerCycleSubscription, { commandId: c.commandId, qc: qc }, c.commandId))), _jsxs("section", { children: [_jsxs("div", { className: "flex items-center justify-between mb-3", children: [_jsxs("h2", { className: "text-lg font-semibold", children: ["\uD65C\uC131 \uBA85\uB839 (", activeQ.isLoading ? "..." : commands.length, ")"] }), commands.length > 0 && (_jsx(SortDropdown, { value: sortKey, onChange: setSortKey }))] }), activeQ.isLoading ? (_jsxs("div", { className: "space-y-2", children: [_jsx(Skeleton, { className: "h-20 w-full" }), _jsx(Skeleton, { className: "h-20 w-full" })] })) : activeQ.isError ? (_jsx(ErrorState, { onRetry: () => activeQ.refetch() })) : commands.length === 0 ? (_jsx(EmptyState, { message: "\uD65C\uC131 \uB9E4\uB9E4 \uBA85\uB839\uC774 \uC5C6\uC2B5\uB2C8\uB2E4", action: _jsx(Link, { to: "/command", className: "px-4 py-2 rounded bg-emerald-700 hover:bg-emerald-600 text-sm text-white", children: "\uB9E4\uB9E4 \uBA85\uB839 \uC791\uC131\uD558\uAE30 \u2192" }) })) : (_jsx("div", { className: "space-y-2", children: sortedCommands.map((cmd) => (_jsx(ActiveRow, { cmd: cmd, selected: selectedId === cmd.commandId, onSelect: () => setSelectedId(cmd.commandId) }, cmd.commandId))) }))] }), selectedId && (_jsxs("section", { children: [_jsxs("h2", { className: "text-lg font-semibold mb-3", children: ["\uC0C1\uC138 ", selectedDetail && `— ${selectedDetail.stockName}`] }), detailQ.isLoading ? (_jsx(Skeleton, { className: "h-64 w-full" })) : detailQ.isError ? (_jsx(ErrorState, { onRetry: () => detailQ.refetch() })) : selectedDetail ? (_jsx(DetailPanel, { detail: selectedDetail, onCancel: () => setShowCancelDialog(true) })) : null] })), _jsxs("section", { children: [_jsxs("div", { className: "flex items-center justify-between mb-3", children: [_jsxs("h3", { className: "text-sm font-semibold text-zinc-400", children: ["\uC624\uB298 \uC885\uB8CC\uB41C \uBA85\uB839 (", filteredTodayClosed.length, reasonFilter.size > 0 && ` / ${todayRows.length}`, ")"] }), todayRows.length > 0 && (_jsx(ReasonFilter, { value: reasonFilter, onChange: setReasonFilter, available: new Set(todayRows
                                    .map((r) => r.closeReason)
                                    .filter((x) => !!x)) }))] }), todayQ.isLoading ? (_jsx(Skeleton, { className: "h-32 w-full" })) : todayQ.isError ? (_jsx(ErrorState, { onRetry: () => todayQ.refetch() })) : filteredTodayClosed.length === 0 ? (_jsx(EmptyState, { icon: "\uD83D\uDCED", message: reasonFilter.size > 0
                            ? "필터 조건에 맞는 종료 항목이 없습니다"
                            : "오늘 종료된 명령이 없습니다" })) : (_jsx(TodayClosedTable, { rows: filteredTodayClosed, emphasizeUnclosed: settings.emphasizeUnclosed, rowRefs: todayRowRefs }))] }), showCancelDialog && selectedDetail && (_jsx(ConfirmDialog, { title: "\uB9E4\uB9E4 \uC0AC\uC774\uD074 \uCDE8\uC18C", message: `${selectedDetail.stockName} 명령을 즉시 청산합니다. 보유 ${formatQty(selectedDetail.holdingQty)}이 시장가로 매도됩니다. 계속할까요?`, onConfirm: onCancelConfirm, onCancel: () => setShowCancelDialog(false), confirmLoading: cancelCommand.isPending }))] }));
}
// ============================================================
// Per-cycle STOMP subscription
// ============================================================
function PerCycleSubscription({ commandId, qc, }) {
    useStompSubscription(`/topic/trading/${commandId}`, (p) => {
        if (p.type === "PRICE") {
            // active list 행 부분 갱신
            qc.setQueryData(QK.activeCommands, (prev) => prev?.map((c) => c.commandId === commandId
                ? {
                    ...c,
                    currentPrice: p.currentPrice,
                    profitRate: Number(p.profitRate),
                    profitAmount: p.profitAmount,
                }
                : c));
            // 상세 캐시도 부분 갱신
            qc.setQueryData(QK.commandDetail(commandId), (prev) => prev
                ? {
                    ...prev,
                    currentPrice: p.currentPrice,
                    profitRate: Number(p.profitRate),
                    profitAmount: p.profitAmount,
                }
                : prev);
        }
        else if (p.type === "STATE") {
            qc.setQueryData(QK.activeCommands, (prev) => prev?.map((c) => c.commandId === commandId ? { ...c, status: p.status } : c));
            qc.invalidateQueries({ queryKey: QK.commandDetail(commandId) });
        }
        else {
            // EXECUTION / SIGNAL / RETRY → 상세 invalidate (active 요약은 일부 필드만)
            if (p.type === "EXECUTION") {
                qc.setQueryData(QK.activeCommands, (prev) => prev?.map((c) => c.commandId === commandId
                    ? {
                        ...c,
                        holdingQty: p.holdingQty,
                        averageBuyPrice: p.averageBuyPrice,
                    }
                    : c));
            }
            qc.invalidateQueries({ queryKey: QK.commandDetail(commandId) });
        }
    });
    return null;
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
function ActiveRow({ cmd, selected, onSelect, }) {
    return (_jsx("button", { onClick: onSelect, className: `w-full text-left rounded-lg border p-4 transition-colors ${selected
            ? "border-emerald-700 bg-zinc-800/60"
            : "border-zinc-800 bg-zinc-900 hover:bg-zinc-800/40"}`, children: _jsxs("div", { className: "grid grid-cols-[auto_1fr_auto_auto] items-center gap-4", children: [_jsx(StatusPill, { status: cmd.status }), _jsxs("div", { className: "min-w-0", children: [_jsxs("div", { className: "font-medium truncate", children: [cmd.stockName, _jsx("span", { className: "text-xs text-zinc-500 ml-2", children: cmd.stockCode })] }), _jsxs("div", { className: "text-xs text-zinc-500 mt-0.5", children: ["\uD3C9\uB2E8 ", formatPrice(cmd.averageBuyPrice), " \u2192 \uD604\uC7AC", " ", formatPrice(cmd.currentPrice)] })] }), _jsxs("div", { className: "text-right", children: [_jsx(FlashOnChange, { value: cmd.profitRate, children: _jsx(ProfitText, { value: cmd.profitRate, format: formatPct, className: "text-lg font-semibold" }) }), _jsx("div", { className: "text-xs", children: _jsx(ProfitText, { value: cmd.profitAmount, format: formatKRW }) })] }), _jsxs("div", { className: "flex flex-col items-end gap-1.5 min-w-[70px]", children: [_jsx(BuyAttemptDots, { completed: cmd.buyAttempt.completed, total: cmd.buyAttempt.total }), _jsxs("div", { className: "text-xs text-zinc-400 flex items-center gap-1", children: [formatQty(cmd.holdingQty), cmd.status === "LIQUIDATING" && (_jsx("span", { title: "\uCCAD\uC0B0 \uC911", "aria-label": "\uCCAD\uC0B0 \uC911", children: "\uD83D\uDD25" }))] })] })] }) }));
}
function BuyAttemptDots({ completed, total, }) {
    // dot은 시도 회차(attempt)를 표시 — 체결 여부는 상세 패널에서 확인.
    // 발송 직후 "체결"로 오해하지 않게 amber 색상 사용.
    return (_jsx("div", { className: "flex gap-1", title: `매수 ${completed}/${total} 회차 시도 (체결 여부는 상세 참조)`, "aria-label": `매수 ${completed}/${total} 회차 시도`, children: Array.from({ length: total }).map((_, i) => (_jsx("span", { className: `w-2 h-2 rounded-full ${i < completed ? "bg-amber-400" : "bg-zinc-700"}` }, i))) }));
}
function ConfirmDialog({ title, message, onConfirm, onCancel, confirmLoading, }) {
    return (_jsx("div", { className: "fixed inset-0 bg-black/70 flex items-center justify-center z-50", children: _jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-6 max-w-md w-full", children: [_jsx("h3", { className: "text-lg font-semibold mb-3", children: title }), _jsx("p", { className: "text-sm text-zinc-300 mb-6", children: message }), _jsxs("div", { className: "flex justify-end gap-2", children: [_jsx("button", { onClick: onCancel, disabled: confirmLoading, className: "px-4 py-2 rounded text-sm bg-zinc-800 hover:bg-zinc-700 disabled:opacity-50", children: "\uB3CC\uC544\uAC00\uAE30" }), _jsx("button", { onClick: onConfirm, disabled: confirmLoading, className: "px-4 py-2 rounded text-sm bg-rose-700 hover:bg-rose-600 text-white font-medium disabled:opacity-50", children: confirmLoading ? "처리 중..." : "취소 진행" })] })] }) }));
}
