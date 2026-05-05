import { jsx as _jsx } from "react/jsx-runtime";
// Mock STOMP 연결 상태 — Phase 5-B-1 UI 개발용.
// Phase 5-B-2에서 실제 @stomp/stompjs 클라이언트로 교체.
import { createContext, useCallback, useContext, useEffect, useState, } from "react";
const Ctx = createContext(null);
export function StompMockProvider({ children }) {
    const [state, setState] = useState("connected");
    const [lastConnectedAt, setLastConnectedAt] = useState(new Date().toISOString());
    const [lastDisconnectedAt, setLastDisconnectedAt] = useState(null);
    useEffect(() => {
        const now = new Date().toISOString();
        if (state === "connected")
            setLastConnectedAt(now);
        if (state === "disconnected")
            setLastDisconnectedAt(now);
    }, [state]);
    const setStateCb = useCallback((s) => setState(s), []);
    return (_jsx(Ctx.Provider, { value: { state, lastConnectedAt, lastDisconnectedAt, setState: setStateCb }, children: children }));
}
export function useStompState() {
    const v = useContext(Ctx);
    if (!v)
        throw new Error("useStompState must be used inside <StompMockProvider>");
    return v;
}
