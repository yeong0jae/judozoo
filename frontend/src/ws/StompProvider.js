import { jsx as _jsx } from "react/jsx-runtime";
import { createContext, useContext, useEffect, useMemo, useState, } from "react";
import { getStompClient } from "./stompClient";
const Ctx = createContext(null);
export function StompProvider({ children, onReconnect, }) {
    const [state, setState] = useState("reconnecting");
    const [lastConnectedAt, setLastConnectedAt] = useState(null);
    const [lastDisconnectedAt, setLastDisconnectedAt] = useState(null);
    const client = useMemo(() => getStompClient(), []);
    useEffect(() => {
        let wasConnected = false;
        client.onConnect = () => {
            const now = new Date().toISOString();
            setState("connected");
            setLastConnectedAt(now);
            if (wasConnected) {
                // 재연결 — 누락 메시지 보강 fetch 트리거
                onReconnect?.();
            }
            wasConnected = true;
        };
        client.onWebSocketClose = () => {
            setState("reconnecting");
            setLastDisconnectedAt(new Date().toISOString());
        };
        client.onStompError = (frame) => {
            console.error("STOMP error", frame.headers, frame.body);
            setState("disconnected");
            setLastDisconnectedAt(new Date().toISOString());
        };
        if (!client.active) {
            client.activate();
        }
        else if (client.connected) {
            setState("connected");
        }
        // 연결을 conversation 전체에 유지 — unmount 시 deactivate 안 함
        return undefined;
    }, [client, onReconnect]);
    const value = useMemo(() => ({ state, lastConnectedAt, lastDisconnectedAt, client }), [state, lastConnectedAt, lastDisconnectedAt, client]);
    return _jsx(Ctx.Provider, { value: value, children: children });
}
export function useStompState() {
    const v = useContext(Ctx);
    if (!v)
        throw new Error("useStompState must be used inside <StompProvider>");
    return v;
}
