import type { TradingCycleStatus } from "../../types";

const STATUS_MAP: Record<TradingCycleStatus, { label: string; cls: string }> = {
  INITIATED: { label: "INITIATED", cls: "bg-zinc-700 text-zinc-300" },
  BUYING: { label: "BUYING", cls: "bg-blue-900/60 text-blue-300" },
  HOLDING: { label: "HOLDING", cls: "bg-emerald-900/60 text-emerald-300" },
  LIQUIDATING: { label: "LIQUIDATING", cls: "bg-amber-900/60 text-amber-300" },
  CLOSED: { label: "CLOSED", cls: "bg-zinc-800 text-zinc-500" },
};

export default function StatusPill({ status }: { status: TradingCycleStatus }) {
  const { label, cls } = STATUS_MAP[status];
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${cls}`}>
      {label}
    </span>
  );
}
