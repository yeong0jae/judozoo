import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useNotifications } from "../../notifications/notifications";
import CloseReasonBadge from "../common/CloseReasonBadge";
import { formatRelative } from "../../lib/format";
export default function NotificationBell() {
    const { events, unreadCount, hasCritical, lastSeenAt, markAllRead } = useNotifications();
    const [open, setOpen] = useState(false);
    const ref = useRef(null);
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
    const recent = events.slice(0, 8);
    return (_jsxs("div", { ref: ref, className: "relative", children: [_jsxs("button", { onClick: () => setOpen((o) => !o), className: "relative flex items-center justify-center w-8 h-8 rounded hover:bg-zinc-800 transition-colors", "aria-label": `알림 ${unreadCount}건`, children: [_jsx("span", { className: "text-base", "aria-hidden": true, children: "\uD83D\uDD14" }), unreadCount > 0 && (_jsx("span", { className: `absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 flex items-center justify-center text-[10px] font-bold rounded-full ${hasCritical
                            ? "bg-rose-600 text-white"
                            : "bg-zinc-200 text-zinc-900"}`, children: unreadCount > 99 ? "99+" : unreadCount }))] }), open && (_jsxs("div", { className: "absolute right-0 mt-1 w-80 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl z-50", children: [_jsxs("div", { className: "px-4 py-3 border-b border-zinc-800 flex items-center justify-between", children: [_jsx("span", { className: "text-sm font-semibold", children: "\uCD5C\uADFC \uC885\uB8CC" }), _jsx("span", { className: "text-xs text-zinc-500", children: unreadCount > 0 ? `${unreadCount}건 미확인` : "모두 확인됨" })] }), _jsx("div", { className: "max-h-80 overflow-y-auto", children: recent.length === 0 ? (_jsx("div", { className: "px-4 py-8 text-center text-sm text-zinc-500", children: "\uC54C\uB9BC\uC774 \uC5C6\uC2B5\uB2C8\uB2E4" })) : (recent.map((e) => {
                            const unread = e.ts > lastSeenAt;
                            return (_jsxs("div", { className: `px-4 py-2.5 border-b border-zinc-800 last:border-0 ${unread ? "bg-zinc-800/40" : ""}`, children: [_jsxs("div", { className: "flex items-center gap-2 mb-1", children: [_jsx(CloseReasonBadge, { reason: e.closeReason }), unread && (_jsx("span", { className: "w-1.5 h-1.5 rounded-full bg-blue-400" }))] }), _jsxs("div", { className: "flex items-baseline justify-between gap-2 text-sm", children: [_jsx("span", { className: "truncate", children: e.stockName ?? `명령 #${e.cycleId}` }), _jsx("span", { className: "text-xs text-zinc-500 shrink-0", children: formatRelative(e.ts) })] })] }, e.id));
                        })) }), _jsxs("div", { className: "px-4 py-2.5 border-t border-zinc-800 flex justify-between gap-2", children: [_jsx("button", { onClick: markAllRead, disabled: unreadCount === 0, className: "text-xs text-zinc-400 hover:text-zinc-200 disabled:opacity-40", children: "\uBAA8\uB450 \uC77D\uC74C" }), _jsx(Link, { to: "/monitoring", onClick: () => {
                                    markAllRead();
                                    setOpen(false);
                                }, className: "text-xs text-emerald-400 hover:text-emerald-300", children: "\uBAA8\uB2C8\uD130\uB9C1\uC5D0\uC11C \uBAA8\uB450 \uBCF4\uAE30 \u2192" })] })] }))] }));
}
