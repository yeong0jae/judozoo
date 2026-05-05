import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import type { Client } from "@stomp/stompjs";
import { getStompClient } from "./stompClient";

export type ConnectionState = "connected" | "reconnecting" | "disconnected";

interface StompContextValue {
  state: ConnectionState;
  lastConnectedAt: string | null;
  lastDisconnectedAt: string | null;
  client: Client;
}

const Ctx = createContext<StompContextValue | null>(null);

export function StompProvider({
  children,
  onReconnect,
}: {
  children: ReactNode;
  onReconnect?: () => void;
}) {
  const [state, setState] = useState<ConnectionState>("reconnecting");
  const [lastConnectedAt, setLastConnectedAt] = useState<string | null>(null);
  const [lastDisconnectedAt, setLastDisconnectedAt] = useState<string | null>(
    null,
  );

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
    } else if (client.connected) {
      setState("connected");
    }

    // 연결을 conversation 전체에 유지 — unmount 시 deactivate 안 함
    return undefined;
  }, [client, onReconnect]);

  const value = useMemo<StompContextValue>(
    () => ({ state, lastConnectedAt, lastDisconnectedAt, client }),
    [state, lastConnectedAt, lastDisconnectedAt, client],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useStompState(): StompContextValue {
  const v = useContext(Ctx);
  if (!v) throw new Error("useStompState must be used inside <StompProvider>");
  return v;
}
