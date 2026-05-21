import type { TradingCycleStatus } from "../../types";

const STATUS_MAP: Record<TradingCycleStatus, { label: string; cls: string }> = {
  INITIATED: { label: "INITIATED", cls: "bg-gray-100 text-gray-700" },
  BUYING: { label: "BUYING", cls: "bg-blue-50 text-blue-700" },
  HOLDING: { label: "HOLDING", cls: "bg-emerald-50 text-emerald-800" },
  LIQUIDATING: { label: "LIQUIDATING", cls: "bg-amber-50 text-amber-800" },
  CLOSED: { label: "CLOSED", cls: "bg-gray-100 text-gray-500" },
};

export default function StatusPill({ status }: { status: TradingCycleStatus }) {
  const { label, cls } = STATUS_MAP[status];
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${cls}`}>
      {label}
    </span>
  );
}
