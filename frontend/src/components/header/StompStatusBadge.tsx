import { useEffect, useRef, useState } from "react";
import { useStompState } from "../../ws/StompProvider";
import { formatRelative } from "../../lib/format";

const META: Record<
  ReturnType<typeof useStompState>["state"],
  { label: string; dot: string; text: string; pulse?: boolean }
> = {
  connected: {
    label: "연결됨",
    dot: "bg-emerald-400",
    text: "text-emerald-300",
  },
  reconnecting: {
    label: "재연결 중",
    dot: "bg-amber-400",
    text: "text-amber-300",
    pulse: true,
  },
  disconnected: {
    label: "끊김",
    dot: "bg-rose-400",
    text: "text-rose-300",
  },
};

export default function StompStatusBadge() {
  const { state, lastConnectedAt, lastDisconnectedAt } = useStompState();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const meta = META[state];

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

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen((o) => !o)}
        className={`flex items-center gap-1.5 px-2.5 py-1 rounded text-xs ${meta.text} hover:bg-zinc-800 transition-colors`}
        aria-label="실시간 연결 상태"
      >
        <span
          className={`w-2 h-2 rounded-full ${meta.dot} ${meta.pulse ? "animate-pulse" : ""}`}
        />
        STOMP {meta.label}
      </button>
      {open && (
        <div className="absolute right-0 mt-1 w-64 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl p-3 z-50 text-sm">
          <div className="text-xs font-semibold text-zinc-500 mb-2 uppercase tracking-wider">
            실시간 연결
          </div>
          <div className="space-y-1.5">
            <Row label="상태" value={meta.label} valueClass={meta.text} />
            {lastConnectedAt && (
              <Row
                label="최근 연결"
                value={formatRelative(lastConnectedAt)}
              />
            )}
            {lastDisconnectedAt && (
              <Row
                label="최근 끊김"
                value={formatRelative(lastDisconnectedAt)}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Row({
  label,
  value,
  valueClass,
}: {
  label: string;
  value: string;
  valueClass?: string;
}) {
  return (
    <div className="flex justify-between gap-3">
      <span className="text-zinc-500">{label}</span>
      <span className={valueClass ?? "text-zinc-200"}>{value}</span>
    </div>
  );
}
