import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useEffect, useRef, useState } from "react";
function deriveSummary(status) {
    if (status.isHoliday)
        return { label: "휴장", tone: "warn" };
    if (!status.tradingHoursOpen)
        return { label: "거래시간 외", tone: "warn" };
    if (status.cutoffPassed)
        return { label: "컷오프 지남", tone: "warn" };
    return { label: "시장 정상", tone: "ok" };
}
function deriveConditions(status) {
    return [
        {
            label: "거래시간 09:00–15:30",
            tone: status.tradingHoursOpen ? "ok" : "warn",
        },
        { label: "휴장 아님", tone: status.isHoliday ? "warn" : "ok" },
        { label: "컷오프 전 (15:20)", tone: status.cutoffPassed ? "warn" : "ok" },
    ];
}
const DOT_CLS = {
    ok: "bg-emerald-400",
    warn: "bg-amber-400",
    danger: "bg-rose-400",
};
const TEXT_CLS = {
    ok: "text-emerald-300",
    warn: "text-amber-300",
    danger: "text-rose-300",
};
export default function MarketStatusBadge({ status }) {
    const [open, setOpen] = useState(false);
    const ref = useRef(null);
    const summary = deriveSummary(status);
    const conditions = deriveConditions(status);
    useEffect(() => {
        if (!open)
            return;
        const handler = (e) => {
            if (ref.current && !ref.current.contains(e.target)) {
                setOpen(false);
            }
        };
        window.addEventListener("mousedown", handler);
        return () => window.removeEventListener("mousedown", handler);
    }, [open]);
    return (_jsxs("div", { ref: ref, className: "relative", children: [_jsxs("button", { onClick: () => setOpen((o) => !o), className: `flex items-center gap-1.5 px-2.5 py-1 rounded text-xs ${TEXT_CLS[summary.tone]} hover:bg-zinc-800 transition-colors`, "aria-label": "\uC2DC\uC7A5 \uC0C1\uD0DC", children: [_jsx("span", { className: `w-2 h-2 rounded-full ${DOT_CLS[summary.tone]}` }), summary.label] }), open && (_jsxs("div", { className: "absolute right-0 mt-1 w-64 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl p-3 z-50", children: [_jsx("div", { className: "text-xs font-semibold text-zinc-500 mb-2 uppercase tracking-wider", children: "\uC2DC\uC7A5 \uC0C1\uD0DC" }), _jsx("div", { className: "space-y-1.5", children: conditions.map((c) => (_jsxs("div", { className: "flex items-center gap-2 text-sm", children: [_jsx("span", { className: `w-1.5 h-1.5 rounded-full shrink-0 ${DOT_CLS[c.tone]}` }), _jsx("span", { className: c.tone === "ok"
                                        ? "text-zinc-200"
                                        : c.tone === "warn"
                                            ? "text-amber-300"
                                            : "text-rose-300", children: c.label })] }, c.label))) })] }))] }));
}
