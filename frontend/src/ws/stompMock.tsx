// Mock STOMP 연결 상태 — Phase 5-B-1 UI 개발용.
// Phase 5-B-2에서 실제 @stomp/stompjs 클라이언트로 교체.

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";

export type ConnectionState = "connected" | "reconnecting" | "disconnected";

interface StompStateValue {
  state: ConnectionState;
  lastConnectedAt: string | null;
  lastDisconnectedAt: string | null;
  setState: (s: ConnectionState) => void;
}

const Ctx = createContext<StompStateValue | null>(null);

export function StompMockProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ConnectionState>("connected");
  const [lastConnectedAt, setLastConnectedAt] = useState<string | null>(
    new Date().toISOString(),
  );
  const [lastDisconnectedAt, setLastDisconnectedAt] = useState<string | null>(
    null,
  );

  useEffect(() => {
    const now = new Date().toISOString();
    if (state === "connected") setLastConnectedAt(now);
    if (state === "disconnected") setLastDisconnectedAt(now);
  }, [state]);

  const setStateCb = useCallback((s: ConnectionState) => setState(s), []);

  return (
    <Ctx.Provider
      value={{ state, lastConnectedAt, lastDisconnectedAt, setState: setStateCb }}
    >
      {children}
    </Ctx.Provider>
  );
}

export function useStompState(): StompStateValue {
  const v = useContext(Ctx);
  if (!v) throw new Error("useStompState must be used inside <StompMockProvider>");
  return v;
}
