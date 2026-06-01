import { useEffect, useRef, useState } from "react";
import type { MarketStatus } from "../../types";

type Tone = "ok" | "warn" | "danger";

interface Condition {
  label: string;
  tone: Tone;
}

function deriveSummary(status: MarketStatus): { label: string; tone: Tone } {
  if (status.isHoliday) return { label: "휴장", tone: "warn" };
  if (!status.tradingHoursOpen) return { label: "거래시간 외", tone: "warn" };
  if (status.cutoffPassed) return { label: "컷오프 지남", tone: "warn" };
  return { label: "시장 정상", tone: "ok" };
}

function deriveConditions(status: MarketStatus): Condition[] {
  return [
    {
      label: "거래시간 08:00–20:00",
      tone: status.tradingHoursOpen ? "ok" : "warn",
    },
    { label: "휴장 아님", tone: status.isHoliday ? "warn" : "ok" },
    { label: "컷오프 전 (19:50)", tone: status.cutoffPassed ? "warn" : "ok" },
  ];
}

const DOT_CLS: Record<Tone, string> = {
  ok: "bg-emerald-500",
  warn: "bg-amber-500",
  danger: "bg-rose-500",
};

const TEXT_CLS: Record<Tone, string> = {
  ok: "text-emerald-800",
  warn: "text-amber-700",
  danger: "text-rose-700",
};

export default function MarketStatusBadge({ status }: { status: MarketStatus }) {
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
        aria-label="시장 상태"
      >
        <span className={`w-2 h-2 rounded-full ${DOT_CLS[summary.tone]}`} />
        {summary.label}
      </button>
      {open && (
        <div className="absolute right-0 mt-1 w-64 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl p-3 z-50">
          <div className="text-xs font-semibold text-zinc-500 mb-2 uppercase tracking-wider">
            시장 상태
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
                        ? "text-amber-700"
                        : "text-rose-700"
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
