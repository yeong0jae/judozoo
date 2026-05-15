import { jsx as _jsx } from "react/jsx-runtime";
import { colorByPnL } from "../../lib/format";
export default function ProfitText({ value, format, className = "", zeroAsDash = false, }) {
    if (value === null || (zeroAsDash && value === 0)) {
        return _jsx("span", { className: "text-zinc-500", children: "-" });
    }
    return (_jsx("span", { className: `${colorByPnL(value)} ${className}`, children: format(value) }));
}
