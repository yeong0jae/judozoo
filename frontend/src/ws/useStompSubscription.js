import { useEffect, useRef } from "react";
import { useStompState } from "./StompProvider";
export function useStompSubscription(destination, handler, enabled = true) {
    const { client, state } = useStompState();
    const handlerRef = useRef(handler);
    useEffect(() => {
        handlerRef.current = handler;
    });
    useEffect(() => {
        if (!enabled)
            return;
        if (state !== "connected" || !client.connected)
            return;
        const sub = client.subscribe(destination, (msg) => {
            try {
                const payload = JSON.parse(msg.body);
                handlerRef.current(payload);
            }
            catch (e) {
                console.error("STOMP payload parse failed", destination, e);
            }
        });
        return () => {
            try {
                sub.unsubscribe();
            }
            catch {
                // already disconnected
            }
        };
    }, [destination, enabled, state, client]);
}
