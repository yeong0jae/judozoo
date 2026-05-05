import { jsx as _jsx } from "react/jsx-runtime";
import { useEffect, useRef, useState } from "react";
import { useStompState } from "../../ws/stompMock";
export default function StompDisconnectionBanner() {
    const { state } = useStompState();
    const [showRecovered, setShowRecovered] = useState(false);
    const prevState = useRef(state);
    useEffect(() => {
        if (prevState.current !== "connected" && state === "connected") {
            setShowRecovered(true);
            const t = setTimeout(() => setShowRecovered(false), 1500);
            prevState.current = state;
            return () => clearTimeout(t);
        }
        prevState.current = state;
    }, [state]);
    if (state === "connected" && !showRecovered)
        return null;
    if (showRecovered) {
        return (_jsx("div", { className: "bg-emerald-900/40 border-b border-emerald-800 text-emerald-200 text-xs text-center py-1.5", children: "\u2713 \uC5F0\uACB0 \uBCF5\uAD6C\uB428" }));
    }
    return (_jsx("div", { className: "bg-amber-900/40 border-b border-amber-800 text-amber-200 text-xs text-center py-1.5", children: "\u26A0 \uC2E4\uC2DC\uAC04 \uB04A\uAE40 \u2014 \uC7AC\uC5F0\uACB0 \uC911..." }));
}
