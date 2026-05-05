import { useEffect, useRef, useState } from "react";
import type { SystemStatus } from "../../types";

type Tone = "ok" | "warn" | "danger";

interface Condition {
  label: string;
  tone: Tone;
}

function deriveSummary(status: SystemStatus): { label: string; tone: Tone } {
  if (status.tokenStatus !== "OK") return { label: "토큰 오류", tone: "danger" };
  if (status.isHoliday) return { label: "휴장", tone: "warn" };
  if (!status.tradingHoursOpen) return { label: "거래시간 외", tone: "warn" };
  if (status.cutoffPassed) return { label: "컷오프 지남", tone: "warn" };
  if (status.marketMode === "POLLING") return { label: "폴링 모드", tone: "warn" };
  return { label: "시스템", tone: "ok" };
}

function deriveConditions(status: SystemStatus): Condition[] {
  return [
    {
      label: "거래시간 09:00–15:30",
      tone: status.tradingHoursOpen ? "ok" : "warn",
    },
    { label: "휴장 아님", tone: status.isHoliday ? "warn" : "ok" },
    { label: "컷오프 전 (15:20)", tone: status.cutoffPassed ? "warn" : "ok" },
    {
      label: "토큰 정상",
      tone: status.tokenStatus === "OK" ? "ok" : "danger",
    },
    {
      label: `시세 모드 ${status.marketMode}`,
      tone: status.marketMode === "WS" ? "ok" : "warn",
    },
  ];
}

const DOT_CLS: Record<Tone, string> = {
  ok: "bg-emerald-400",
  warn: "bg-amber-400",
  danger: "bg-rose-400",
};

const TEXT_CLS: Record<Tone, string> = {
  ok: "text-emerald-300",
  warn: "text-amber-300",
  danger: "text-rose-300",
};

export default function SystemStatusBadge({ status }: { status: SystemStatus }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const summary = deriveSummary(status);
  const conditions = deriveConditions(status);

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
        className={`flex items-center gap-1.5 px-2.5 py-1 rounded text-xs ${TEXT_CLS[summary.tone]} hover:bg-zinc-800 transition-colors`}
        aria-label="시스템 상태"
      >
        <span className={`w-2 h-2 rounded-full ${DOT_CLS[summary.tone]}`} />
        {summary.label}
      </button>
      {open && (
        <div className="absolute right-0 mt-1 w-64 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl p-3 z-50">
          <div className="text-xs font-semibold text-zinc-500 mb-2 uppercase tracking-wider">
            시스템 상태
          </div>
          <div className="space-y-1.5">
            {conditions.map((c) => (
              <div
                key={c.label}
                className="flex items-center gap-2 text-sm"
              >
                <span
                  className={`w-1.5 h-1.5 rounded-full shrink-0 ${DOT_CLS[c.tone]}`}
                />
                <span
                  className={
                    c.tone === "ok"
                      ? "text-zinc-200"
                      : c.tone === "warn"
                        ? "text-amber-300"
                        : "text-rose-300"
                  }
                >
                  {c.label}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
