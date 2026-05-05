import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useEffect, useRef, useState } from "react";
import { useStompState } from "../../ws/StompProvider";
import { formatRelative } from "../../lib/format";
const META = {
    connected: {
        label: "연결됨",
        dot: "bg-emerald-400",
        text: "text-emerald-300",
    },
    reconnecting: {
        label: "재연결 중",
        dot: "bg-amber-400",
        text: "text-amber-300",
        pulse: true,
    },
    disconnected: {
        label: "끊김",
        dot: "bg-rose-400",
        text: "text-rose-300",
    },
};
export default function StompStatusBadge() {
    const { state, lastConnectedAt, lastDisconnectedAt } = useStompState();
    const [open, setOpen] = useState(false);
    const ref = useRef(null);
    const meta = META[state];
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
    return (_jsxs("div", { ref: ref, className: "relative", children: [_jsxs("button", { onClick: () => setOpen((o) => !o), className: `flex items-center gap-1.5 px-2.5 py-1 rounded text-xs ${meta.text} hover:bg-zinc-800 transition-colors`, "aria-label": "\uC2E4\uC2DC\uAC04 \uC5F0\uACB0 \uC0C1\uD0DC", children: [_jsx("span", { className: `w-2 h-2 rounded-full ${meta.dot} ${meta.pulse ? "animate-pulse" : ""}` }), "STOMP ", meta.label] }), open && (_jsxs("div", { className: "absolute right-0 mt-1 w-64 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl p-3 z-50 text-sm", children: [_jsx("div", { className: "text-xs font-semibold text-zinc-500 mb-2 uppercase tracking-wider", children: "\uC2E4\uC2DC\uAC04 \uC5F0\uACB0" }), _jsxs("div", { className: "space-y-1.5", children: [_jsx(Row, { label: "\uC0C1\uD0DC", value: meta.label, valueClass: meta.text }), lastConnectedAt && (_jsx(Row, { label: "\uCD5C\uADFC \uC5F0\uACB0", value: formatRelative(lastConnectedAt) })), lastDisconnectedAt && (_jsx(Row, { label: "\uCD5C\uADFC \uB04A\uAE40", value: formatRelative(lastDisconnectedAt) }))] })] }))] }));
}
function Row({ label, value, valueClass, }) {
    return (_jsxs("div", { className: "flex justify-between gap-3", children: [_jsx("span", { className: "text-zinc-500", children: label }), _jsx("span", { className: valueClass ?? "text-zinc-200", children: value })] }));
}
