import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { createContext, useCallback, useContext, useRef, useState, } from "react";
import CloseReasonBadge from "../common/CloseReasonBadge";
const ToastContext = createContext(null);
const DEFAULT_DURATION = {
    success: 3000,
    error: 6000,
    warning: 4000,
    info: 3000,
};
const MAX_VISIBLE = 3;
export function ToastProvider({ children }) {
    const [toasts, setToasts] = useState([]);
    const idRef = useRef(0);
    const dismiss = useCallback((id) => {
        setToasts((prev) => prev.filter((t) => t.id !== id));
    }, []);
    const show = useCallback((input) => {
        const id = ++idRef.current;
        const tone = input.tone ?? "info";
        const duration = input.duration ?? (input.closeReason ? 4000 : DEFAULT_DURATION[tone]);
        setToasts((prev) => [...prev, { ...input, id, tone }]);
        if (duration > 0) {
            setTimeout(() => dismiss(id), duration);
        }
        return id;
    }, [dismiss]);
    return (_jsxs(ToastContext.Provider, { value: { show, dismiss }, children: [children, _jsx(ToastContainer, { toasts: toasts, dismiss: dismiss })] }));
}
export function useToast() {
    const ctx = useContext(ToastContext);
    if (!ctx)
        throw new Error("useToast must be used inside <ToastProvider>");
    return ctx;
}
const TONE_CLS = {
    success: "bg-emerald-950/90 border-emerald-800 text-emerald-100",
    error: "bg-rose-950/90 border-rose-800 text-rose-100",
    warning: "bg-amber-950/90 border-amber-800 text-amber-100",
    info: "bg-zinc-900/95 border-zinc-700 text-zinc-100",
};
function ToastContainer({ toasts, dismiss, }) {
    const visible = toasts.slice(-MAX_VISIBLE);
    const overflow = toasts.length - visible.length;
    return (_jsxs("div", { className: "fixed bottom-6 right-6 flex flex-col gap-2 z-50 w-80 pointer-events-none", children: [overflow > 0 && (_jsxs("div", { className: "text-center text-xs text-zinc-500 bg-zinc-900 border border-zinc-800 rounded px-3 py-1 pointer-events-auto", children: ["+", overflow, "\uAC1C \uC54C\uB9BC \uB354\uBCF4\uAE30"] })), visible.map((t) => (_jsx(ToastCard, { toast: t, onClose: () => dismiss(t.id) }, t.id)))] }));
}
function ToastCard({ toast, onClose }) {
    const tone = toast.tone ?? "info";
    const cls = toast.closeReason
        ? "bg-zinc-900/95 border-zinc-700 text-zinc-100"
        : TONE_CLS[tone];
    return (_jsx("div", { className: `border rounded-lg shadow-lg px-4 py-3 pointer-events-auto ${cls}`, role: "status", children: _jsxs("div", { className: "flex items-start justify-between gap-3", children: [_jsxs("div", { className: "flex-1 min-w-0", children: [toast.closeReason && (_jsx("div", { className: "mb-1.5", children: _jsx(CloseReasonBadge, { reason: toast.closeReason }) })), _jsx("p", { className: "text-sm leading-snug", children: toast.message }), toast.action && (_jsx("button", { onClick: toast.action.onClick, className: "mt-2 text-xs underline text-zinc-300 hover:text-white", children: toast.action.label }))] }), _jsx("button", { onClick: onClose, "aria-label": "\uB2EB\uAE30", className: "text-zinc-500 hover:text-zinc-200 leading-none -mt-0.5", children: "\u00D7" })] }) }));
}
