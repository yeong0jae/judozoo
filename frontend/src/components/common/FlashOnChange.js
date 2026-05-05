import { jsx as _jsx } from "react/jsx-runtime";
import { useEffect, useRef, useState } from "react";
// 값이 바뀌면 0.3s 동안 색 flash. 한국 거래소 관행: 상승=빨강, 하락=파랑.
export default function FlashOnChange({ value, children, duration = 300, }) {
    const [flash, setFlash] = useState(null);
    const prev = useRef(value);
    useEffect(() => {
        if (value > prev.current)
            setFlash("up");
        else if (value < prev.current)
            setFlash("down");
        prev.current = value;
    }, [value]);
    useEffect(() => {
        if (!flash)
            return;
        const t = setTimeout(() => setFlash(null), duration);
        return () => clearTimeout(t);
    }, [flash, duration]);
    const cls = flash === "up"
        ? "bg-rose-500/20"
        : flash === "down"
            ? "bg-blue-500/20"
            : "";
    return (_jsx("span", { className: `inline-block transition-colors duration-300 px-1 rounded ${cls}`, children: children }));
}
