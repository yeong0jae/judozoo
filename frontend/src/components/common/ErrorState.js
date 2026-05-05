import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
export default function ErrorState({ message = "데이터를 불러올 수 없습니다", onRetry, }) {
    return (_jsxs("div", { className: "flex flex-col items-center justify-center py-12 text-center", children: [_jsx("div", { className: "text-4xl mb-3 opacity-60", "aria-hidden": "true", children: "\u26A0\uFE0F" }), _jsx("p", { className: "text-sm text-rose-300 mb-4", children: message }), onRetry && (_jsx("button", { onClick: onRetry, className: "px-4 py-2 rounded text-sm bg-zinc-800 hover:bg-zinc-700 border border-zinc-700", children: "\uB2E4\uC2DC \uC2DC\uB3C4" }))] }));
}
