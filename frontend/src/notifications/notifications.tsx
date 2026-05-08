// 사이클 종료(lifecycle CLOSED) 인지 채널의 메모리/localStorage 상태.
// Phase 5-B-2에서 실 STOMP /topic/trading/lifecycle 구독으로 add() 트리거.

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import type { CloseReason } from "../types";

export interface NotificationEvent {
  id: number;
  ts: string;
  cycleId: number;
  closeReason: CloseReason;
  stockName?: string;
  stockCode?: string;
}

interface NotificationsValue {
  events: NotificationEvent[]; // 최신순
  unreadCount: number;
  hasCritical: boolean; // UNCLOSED / NO_FILL 미확인 존재
  lastSeenAt: string;
  add: (event: Omit<NotificationEvent, "id">) => void;
  markAllRead: () => void;
}

const Ctx = createContext<NotificationsValue | null>(null);

const STORAGE_KEY = "at.notifications.lastSeen";
const MAX_KEEP = 50;

export function NotificationProvider({ children }: { children: ReactNode }) {
  const [events, setEvents] = useState<NotificationEvent[]>([]);
  const [lastSeenAt, setLastSeenAt] = useState<string>(() => {
    return (
      (typeof localStorage !== "undefined" && localStorage.getItem(STORAGE_KEY)) ||
      new Date(0).toISOString()
    );
  });
  const idRef = useRef(0);

  const add = useCallback((event: Omit<NotificationEvent, "id">) => {
    setEvents((prev) =>
      [{ ...event, id: ++idRef.current }, ...prev].slice(0, MAX_KEEP),
    );
  }, []);

  const markAllRead = useCallback(() => {
    const now = new Date().toISOString();
    setLastSeenAt(now);
    try {
      localStorage.setItem(STORAGE_KEY, now);
    } catch {
      // localStorage 실패는 무시 (privacy mode 등)
    }
  }, []);

  const value = useMemo<NotificationsValue>(() => {
    const unread = events.filter((e) => e.ts > lastSeenAt);
    return {
      events,
      unreadCount: unread.length,
      hasCritical: unread.some(
        (e) => e.closeReason === "UNCLOSED" || e.closeReason === "NO_FILL",
      ),
      lastSeenAt,
      add,
      markAllRead,
    };
  }, [events, lastSeenAt, add, markAllRead]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useNotifications(): NotificationsValue {
  const v = useContext(Ctx);
  if (!v) throw new Error("useNotifications must be used inside <NotificationProvider>");
  return v;
}
