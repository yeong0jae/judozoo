import type { CloseReason } from "../../types";

interface ReasonStyle {
  icon: string;
  label: string;
  cls: string;
}

const REASON_MAP: Record<CloseReason, ReasonStyle> = {
  TAKE_PROFIT: {
    icon: "✅",
    label: "익절",
    cls: "bg-emerald-50 text-emerald-800 border-emerald-200",
  },
  STOP_LOSS: {
    icon: "❌",
    label: "손절",
    cls: "bg-rose-50 text-rose-700 border-rose-200",
  },
  BREAKEVEN: {
    icon: "⚪",
    label: "본전",
    cls: "bg-gray-100 text-gray-700 border-gray-200",
  },
  TREND_BREAK: {
    icon: "🟠",
    label: "추세 꺾임",
    cls: "bg-orange-50 text-orange-700 border-orange-200",
  },
  MARKET_CLOSE: {
    icon: "🕒",
    label: "장 마감",
    cls: "bg-blue-50 text-blue-700 border-blue-200",
  },
  CANCELLED: {
    icon: "⏹",
    label: "취소",
    cls: "bg-gray-100 text-gray-500 border-gray-200",
  },
  NO_FILL: {
    icon: "⚠️",
    label: "미체결",
    cls: "bg-amber-50 text-amber-800 border-amber-200",
  },
  UNCLOSED: {
    icon: "🚨",
    label: "미마감",
    cls: "bg-rose-50 text-rose-700 border-rose-200",
  },
};

export default function CloseReasonBadge({
  reason,
  showLabel = true,
}: {
  reason: CloseReason;
  showLabel?: boolean;
}) {
  const { icon, label, cls } = REASON_MAP[reason];
  return (
    <span
      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs border ${cls}`}
      title={reason}
    >
      <span>{icon}</span>
      {showLabel && <span>{label}</span>}
    </span>
  );
}

export function isAnomalyReason(reason: CloseReason): boolean {
  return reason === "NO_FILL" || reason === "UNCLOSED";
}
