import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { formatDateTime, formatKRW, formatPct, formatPrice, formatQty, formatTime, } from "../../lib/format";
import StatusPill from "../common/StatusPill";
import ProfitText from "../common/ProfitText";
import CloseReasonBadge from "../common/CloseReasonBadge";
// 사이클 상세 — 모니터링/실적 양쪽에서 재사용.
// `live: false`는 종료된 사이클 (실적 화면에서 사용) — 취소 버튼 미표시.
export default function DetailPanel({ detail, onCancel, live = true, }) {
    return (_jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden", children: [_jsx(SummaryHeader, { detail: detail, onCancel: onCancel, live: live }), _jsxs("div", { className: "p-6 space-y-6", children: [_jsx(ActiveSellAlert, { orders: detail.orders }), _jsx(BuyProgressSection, { detail: detail }), _jsx(SignalArmingBoard, { detail: detail }), _jsx(SplitSellSection, { detail: detail }), _jsx(OrderHistory, { orders: detail.orders }), _jsx(ExecutionHistory, { executions: detail.executions })] })] }));
}
// 활성 SELL 주문(아직 미체결) 중 재시도 ≥ 1 있으면 상단 강조.
// retryCount ≥ 3 이면 더 강한 색상.
function ActiveSellAlert({ orders }) {
    const activeSell = orders
        .filter((o) => o.side === "SELL" && o.status !== "FILLED" && o.status !== "CANCELLED")
        .sort((a, b) => b.submittedAt.localeCompare(a.submittedAt))[0];
    if (!activeSell || activeSell.retryCount === 0)
        return null;
    const critical = activeSell.retryCount >= 3;
    const cls = critical
        ? "bg-rose-950/60 border-rose-800 text-rose-200"
        : "bg-amber-950/40 border-amber-800/60 text-amber-200";
    return (_jsxs("div", { className: `border rounded-md px-4 py-3 text-sm ${cls}`, children: [_jsxs("div", { className: "flex items-center gap-2 font-medium", children: [_jsx("span", { children: "\uD83D\uDD25" }), _jsxs("span", { children: ["\uB9E4\uB3C4 \uC7AC\uC2DC\uB3C4 ", activeSell.retryCount, "\uD68C", " ", critical && _jsx("span", { className: "text-xs ml-1", children: "(3\uD68C \uC774\uC0C1 \u2014 \uC810\uAC80 \uD544\uC694)" })] })] }), activeSell.lastError && (_jsx("div", { className: "text-xs mt-1 text-zinc-300", children: activeSell.lastError })), _jsxs("div", { className: "text-xs mt-1 text-zinc-500", children: ["\uD2B8\uB9AC\uAC70 ", activeSell.trigger, " \u00B7 \uC8FC\uBB38 ", activeSell.orderQty, "\uC8FC / \uCCB4\uACB0", " ", activeSell.filledQty, "\uC8FC"] })] }));
}
function SummaryHeader({ detail, onCancel, live, }) {
    const isClosed = detail.status === "CLOSED";
    return (_jsx("div", { className: "bg-zinc-950 px-6 py-5 border-b border-zinc-800", children: _jsxs("div", { className: "flex items-start justify-between gap-4 flex-wrap", children: [_jsxs("div", { className: "min-w-0", children: [_jsxs("div", { className: "flex items-center gap-2 mb-2 flex-wrap", children: [_jsx(StatusPill, { status: detail.status }), isClosed && detail.closeReason && (_jsx(CloseReasonBadge, { reason: detail.closeReason })), _jsxs("h2", { className: "text-xl font-semibold", children: [detail.stockName, _jsx("span", { className: "text-sm text-zinc-500 ml-2", children: detail.stockCode })] })] }), _jsxs("div", { className: "flex gap-x-6 gap-y-1 text-sm text-zinc-400 flex-wrap", children: [_jsxs("span", { children: ["\uD3C9\uB2E8", " ", _jsxs("span", { className: "text-zinc-200", children: [formatPrice(detail.averageBuyPrice), "\uC6D0"] })] }), _jsxs("span", { children: ["\uD604\uC7AC", " ", _jsxs("span", { className: "text-zinc-200", children: [formatPrice(detail.currentPrice), "\uC6D0"] })] }), _jsxs("span", { children: ["\uBCF4\uC720", " ", _jsx("span", { className: "text-zinc-200", children: formatQty(detail.holdingQty) })] }), _jsxs("span", { children: ["\uB204\uC801 \uB9E4\uC218", " ", _jsx("span", { className: "text-zinc-200", children: formatQty(detail.totalBoughtQty) })] })] })] }), _jsxs("div", { className: "flex items-start gap-4", children: [_jsxs("div", { className: "text-right", children: [_jsx(ProfitText, { value: detail.profitRate, format: formatPct, className: "text-2xl font-bold block" }), _jsx(ProfitText, { value: detail.profitAmount, format: formatKRW, className: "text-sm" })] }), !isClosed && live && onCancel && (_jsx("button", { onClick: onCancel, className: "bg-rose-900/40 hover:bg-rose-900/60 border border-rose-800 text-rose-200 px-3 py-1.5 rounded text-sm font-medium transition-colors whitespace-nowrap", children: "\uCDE8\uC18C" }))] })] }) }));
}
function BuyProgressSection({ detail }) {
    // 회차별 주문은 trigger=BUY_N으로 매칭. cycle.buyAttempt(시도 회차)에 의존하지 않고
    // 실제 Order.status/filledQty로부터 상태 도출 — 발송 직후 PENDING을 "체결"로 오해하지 않게.
    const buyOrders = detail.orders.filter((o) => o.side === "BUY");
    const orderByRound = new Map();
    for (const o of buyOrders) {
        const m = o.trigger.match(/^BUY_(\d+)$/);
        if (m)
            orderByRound.set(parseInt(m[1], 10), o);
    }
    const lastBuy = buyOrders.sort((a, b) => b.submittedAt.localeCompare(a.submittedAt))[0];
    const isActive = detail.status === "INITIATED" || detail.status === "BUYING";
    // 다음 매수 예정 회차 = 아직 주문 없는 가장 작은 회차 번호
    const nextRound = (() => {
        for (let r = 1; r <= detail.buyAttempt.total; r++) {
            if (!orderByRound.has(r))
                return r;
        }
        return null;
    })();
    const nextBuyAt = lastBuy && nextRound !== null && isActive
        ? new Date(new Date(lastBuy.submittedAt).getTime() +
            detail.buyIntervalMin * 60_000)
        : null;
    return (_jsxs(Section, { title: "\uB9E4\uC218 \uC9C4\uD589", children: [_jsx("div", { className: "grid grid-cols-3 gap-3", children: Array.from({ length: detail.buyAttempt.total }).map((_, i) => {
                    const round = i + 1;
                    const order = orderByRound.get(round) ?? null;
                    const isNext = order === null && round === nextRound && nextBuyAt;
                    const view = roundView(order, isNext ? "next" : "idle");
                    return (_jsxs("div", { className: `border rounded p-3 ${view.boxCls}`, children: [_jsxs("div", { className: "text-xs text-zinc-500 mb-1", children: ["\uD68C\uCC28 ", round] }), _jsx("div", { className: `text-sm font-medium ${view.textCls}`, children: view.label }), view.subLabel && (_jsx("div", { className: "text-xs text-zinc-400 mt-1", children: view.subLabel })), isNext && nextBuyAt && (_jsxs("div", { className: "text-xs text-amber-300 mt-1", children: ["~", formatTime(nextBuyAt)] })), order?.lastError && (_jsx("div", { className: "text-xs text-rose-400 mt-1 truncate", title: order.lastError, children: order.lastError }))] }, round));
                }) }), _jsxs("div", { className: "text-xs text-zinc-500 mt-2", children: ["1\uD68C \uB9E4\uC218", " ", detail.perBuyQty != null
                        ? `${detail.perBuyQty}주 (≈ ${formatKRW(detail.perBuyAmount)})`
                        : formatKRW(detail.perBuyAmount), " ", "\u00B7 \uAC04\uACA9 ", detail.buyIntervalMin, "\uBD84"] })] }));
}
function roundView(order, fallback) {
    if (order === null) {
        if (fallback === "next") {
            return {
                label: "⏱ 다음 매수",
                subLabel: null,
                boxCls: "border-amber-700/60 bg-amber-950/30",
                textCls: "",
            };
        }
        return {
            label: "대기",
            subLabel: null,
            boxCls: "border-zinc-800 bg-zinc-950",
            textCls: "text-zinc-400",
        };
    }
    switch (order.status) {
        case "FILLED":
            return {
                label: "✓ 체결",
                subLabel: `${order.filledQty}주`,
                boxCls: "border-emerald-700/60 bg-emerald-950/30",
                textCls: "",
            };
        case "PENDING":
            return order.filledQty > 0
                ? {
                    label: "△ 부분 체결",
                    subLabel: `${order.filledQty}/${order.orderQty}주`,
                    boxCls: "border-amber-700/60 bg-amber-950/30",
                    textCls: "text-amber-200",
                }
                : {
                    label: "⏳ 발송됨",
                    subLabel: `${order.orderQty}주 대기`,
                    boxCls: "border-sky-800/60 bg-sky-950/30",
                    textCls: "text-sky-200",
                };
        case "FAILED":
            return {
                label: "✗ 발송 실패",
                subLabel: null,
                boxCls: "border-rose-800/60 bg-rose-950/30",
                textCls: "text-rose-200",
            };
        case "CANCELLED":
            return {
                label: "− 취소됨",
                subLabel: order.filledQty > 0 ? `${order.filledQty}주 체결` : null,
                boxCls: "border-zinc-700 bg-zinc-900",
                textCls: "text-zinc-400",
            };
        case "NEEDS_REVIEW":
            return {
                label: "⚠ 확인 필요",
                subLabel: null,
                boxCls: "border-rose-800/60 bg-rose-950/30",
                textCls: "text-rose-200",
            };
        default:
            return {
                label: order.status,
                subLabel: null,
                boxCls: "border-zinc-800 bg-zinc-950",
                textCls: "text-zinc-300",
            };
    }
}
function SignalArmingBoard({ detail }) {
    return (_jsx(Section, { title: "\uC2DC\uADF8\uB110 \uBB34\uC7A5", children: _jsxs("div", { className: "grid grid-cols-1 md:grid-cols-3 gap-3", children: [_jsx(ArmCard, { label: "\uC775\uC808 \uB2E8\uACC4", children: _jsxs("div", { className: "flex gap-1.5", children: [_jsx(Stage, { label: "2%", fired: detail.tpStages.fired2pct }), _jsx(Stage, { label: "3%", fired: detail.tpStages.fired3pct }), _jsx(Stage, { label: "5%", fired: detail.tpStages.fired5pct })] }) }), _jsx(ArmCard, { label: "\uBCF8\uC804 \uB9E4\uB3C4", children: _jsx(ArmStatus, { armed: detail.breakevenArmed, icon: "\uD83D\uDEE1" }) }), _jsx(ArmCard, { label: "\uCD94\uC138 \uAEBE\uC784", children: _jsx(ArmStatus, { armed: detail.trendBreakArmed, icon: "\uD83D\uDCC9" }) })] }) }));
}
function SplitSellSection({ detail }) {
    const pct = detail.splitSellProgress.soldPct;
    const totalSoldQty = detail.executions
        .filter((e) => detail.orders.find((o) => o.id === e.orderId)?.side === "SELL")
        .reduce((sum, e) => sum + e.executedQty, 0);
    return (_jsxs(Section, { title: "\uBD84\uD560 \uB9E4\uB3C4 \uC9C4\uD589", children: [_jsxs("div", { className: "flex items-center gap-3", children: [_jsx("div", { className: "flex-1 bg-zinc-800 rounded-full h-2 overflow-hidden", children: _jsx("div", { className: "h-full bg-emerald-600 transition-all", style: { width: `${pct}%` } }) }), _jsxs("span", { className: "text-sm text-zinc-300 whitespace-nowrap", children: [pct, "% (", formatQty(totalSoldQty), " /", " ", formatQty(detail.totalBoughtQty), ")"] })] }), _jsxs("div", { className: "text-xs text-zinc-500 mt-2", children: ["\uBD84\uD560 \uBE44\uC728 ", (detail.splitSellRatio * 100).toFixed(0), "% / \uD68C \u00B7 \uC911\uB3C4 \uC775\uC808 +", detail.midwayProfitPct, "% \u00B7 \uBCF8\uC804 +", detail.breakevenThresholdPct, "% \u00B7 \uC190\uC808 -", detail.stopLossPct, "%"] })] }));
}
function OrderHistory({ orders }) {
    if (orders.length === 0)
        return null;
    return (_jsx(Section, { title: `주문 이력 (${orders.length})`, children: _jsx("div", { className: "overflow-x-auto -mx-2", children: _jsxs("table", { className: "w-full text-xs", children: [_jsx("thead", { className: "text-zinc-500 uppercase", children: _jsxs("tr", { children: [_jsx("th", { className: "text-left px-2 py-2 font-medium", children: "\uC81C\uCD9C\uC2DC\uAC01" }), _jsx("th", { className: "text-left px-2 py-2 font-medium", children: "\uBC29\uD5A5" }), _jsx("th", { className: "text-left px-2 py-2 font-medium", children: "\uD2B8\uB9AC\uAC70" }), _jsx("th", { className: "text-right px-2 py-2 font-medium", children: "\uC8FC\uBB38" }), _jsx("th", { className: "text-right px-2 py-2 font-medium", children: "\uCCB4\uACB0" }), _jsx("th", { className: "text-left px-2 py-2 font-medium", children: "\uC0C1\uD0DC" }), _jsx("th", { className: "text-right px-2 py-2 font-medium", children: "\uC7AC\uC2DC\uB3C4" })] }) }), _jsx("tbody", { children: orders.map((o) => {
                            const highlight = o.side === "SELL" &&
                                o.status !== "FILLED" &&
                                o.status !== "CANCELLED" &&
                                o.retryCount >= 3;
                            return (_jsxs("tr", { className: `border-t border-zinc-800 ${highlight ? "bg-rose-950/30" : ""}`, children: [_jsx("td", { className: "px-2 py-2 text-zinc-400 whitespace-nowrap", children: formatDateTime(o.submittedAt) }), _jsx("td", { className: "px-2 py-2", children: _jsx(SideBadge, { side: o.side }) }), _jsx("td", { className: "px-2 py-2 text-zinc-400", children: o.trigger }), _jsx("td", { className: "px-2 py-2 text-right", children: o.orderQty }), _jsx("td", { className: "px-2 py-2 text-right", children: o.filledQty }), _jsx("td", { className: "px-2 py-2", children: _jsx(OrderStatusText, { status: o.status }) }), _jsx("td", { className: "px-2 py-2 text-right", children: o.retryCount > 0 ? (_jsxs("span", { className: o.retryCount >= 3
                                                ? "text-rose-300 font-medium"
                                                : "text-amber-300", title: o.lastError ?? undefined, children: [o.retryCount, "\uD68C"] })) : (_jsx("span", { className: "text-zinc-600", children: "-" })) })] }, o.id));
                        }) })] }) }) }));
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
