import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
export default function EmptyState({ icon = "📭", message, action }) {
    return (_jsxs("div", { className: "flex flex-col items-center justify-center py-12 text-center", children: [_jsx("div", { className: "text-4xl mb-3 opacity-60", "aria-hidden": "true", children: icon }), _jsx("p", { className: "text-sm text-zinc-500 mb-4", children: message }), action] }));
}
