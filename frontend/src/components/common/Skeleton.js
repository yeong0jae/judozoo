import { jsx as _jsx } from "react/jsx-runtime";
export default function Skeleton({ className = "" }) {
    return (_jsx("div", { className: `bg-zinc-800/60 rounded animate-pulse ${className}`, "aria-hidden": "true" }));
}
