import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useNotifications } from "../../notifications/notifications";
import CloseReasonBadge from "../common/CloseReasonBadge";
import { formatRelative } from "../../lib/format";

export default function NotificationBell() {
  const { events, unreadCount, hasCritical, lastSeenAt, markAllRead } =
    useNotifications();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    window.addEventListener("mousedown", handler);
    return () => window.removeEventListener("mousedown", handler);
  }, [open]);

  const recent = events.slice(0, 8);

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen((o) => !o)}
        className="relative flex items-center justify-center w-8 h-8 rounded hover:bg-zinc-800 transition-colors"
        aria-label={`알림 ${unreadCount}건`}
      >
        <span className="text-base" aria-hidden>🔔</span>
        {unreadCount > 0 && (
          <span
            className={`absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 flex items-center justify-center text-[10px] font-bold rounded-full ${
              hasCritical
                ? "bg-rose-600 text-white"
                : "bg-zinc-200 text-zinc-900"
            }`}
          >
            {unreadCount > 99 ? "99+" : unreadCount}
          </span>
        )}
      </button>
      {open && (
        <div className="absolute right-0 mt-1 w-80 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl z-50">
          <div className="px-4 py-3 border-b border-zinc-800 flex items-center justify-between">
            <span className="text-sm font-semibold">최근 종료</span>
            <span className="text-xs text-zinc-500">
              {unreadCount > 0 ? `${unreadCount}건 미확인` : "모두 확인됨"}
            </span>
          </div>
          <div className="max-h-80 overflow-y-auto">
            {recent.length === 0 ? (
              <div className="px-4 py-8 text-center text-sm text-zinc-500">
                알림이 없습니다
              </div>
            ) : (
              recent.map((e) => {
                const unread = e.ts > lastSeenAt;
                return (
                  <div
                    key={e.id}
                    className={`px-4 py-2.5 border-b border-zinc-800 last:border-0 ${
                      unread ? "bg-zinc-800/40" : ""
                    }`}
                  >
                    <div className="flex items-center gap-2 mb-1">
                      <CloseReasonBadge reason={e.closeReason} />
                      {unread && (
                        <span className="w-1.5 h-1.5 rounded-full bg-blue-400" />
                      )}
                    </div>
                    <div className="flex items-baseline justify-between gap-2 text-sm">
                      <span className="truncate">
                        {e.stockName ?? `명령 #${e.cycleId}`}
                      </span>
                      <span className="text-xs text-zinc-500 shrink-0">
                        {formatRelative(e.ts)}
                      </span>
                    </div>
                  </div>
                );
              })
            )}
          </div>
          <div className="px-4 py-2.5 border-t border-zinc-800 flex justify-between gap-2">
            <button
              onClick={markAllRead}
              disabled={unreadCount === 0}
              className="text-xs text-zinc-400 hover:text-zinc-200 disabled:opacity-40"
            >
              모두 읽음
            </button>
            <Link
              to="/monitoring"
              onClick={() => {
                markAllRead();
                setOpen(false);
              }}
              className="text-xs text-blue-600 hover:text-blue-700"
            >
              모니터링에서 모두 보기 →
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}
