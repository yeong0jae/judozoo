import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
const REASON_MAP = {
    TAKE_PROFIT: {
        icon: "✅",
        label: "익절",
        cls: "bg-emerald-900/40 text-emerald-300 border-emerald-800",
    },
    STOP_LOSS: {
        icon: "❌",
        label: "손절",
        cls: "bg-rose-900/40 text-rose-300 border-rose-800",
    },
    BREAKEVEN: {
        icon: "⚪",
        label: "본전",
        cls: "bg-zinc-800 text-zinc-300 border-zinc-700",
    },
    TREND_BREAK: {
        icon: "🟠",
        label: "추세 꺾임",
        cls: "bg-orange-900/40 text-orange-300 border-orange-800",
    },
    MARKET_CLOSE: {
        icon: "🕒",
        label: "장 마감",
        cls: "bg-blue-900/40 text-blue-300 border-blue-800",
    },
    CANCELLED: {
        icon: "⏹",
        label: "취소",
        cls: "bg-zinc-800 text-zinc-400 border-zinc-700",
    },
    NO_FILL: {
        icon: "⚠️",
        label: "미체결",
        cls: "bg-amber-900/40 text-amber-300 border-amber-700",
    },
    UNCLOSED: {
        icon: "🚨",
        label: "미마감",
        cls: "bg-rose-900/60 text-rose-200 border-rose-700",
    },
};
export default function CloseReasonBadge({ reason, showLabel = true, }) {
    const { icon, label, cls } = REASON_MAP[reason];
    return (_jsxs("span", { className: `inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs border ${cls}`, title: reason, children: [_jsx("span", { children: icon }), showLabel && _jsx("span", { children: label })] }));
}
export function isAnomalyReason(reason) {
    return reason === "NO_FILL" || reason === "UNCLOSED";
}
