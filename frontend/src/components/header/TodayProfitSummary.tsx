import { useTodayClosed } from "../../api/queries";
import { colorByPnL, formatKRW, formatPct } from "../../lib/format";

export default function TodayProfitSummary() {
  const { data, isLoading } = useTodayClosed();
  const rows = data ?? [];
  const totalProfit = rows.reduce((s, r) => s + r.profitAmount, 0);
  const totalBase = rows.reduce(
    (s, r) =>
      s +
      (r.profitAmount === 0
        ? 0
        : Math.abs(r.profitAmount / Math.max(r.profitRate, 0.0001))),
    0,
  );
  const aggregateRate = totalBase > 0 ? totalProfit / totalBase : 0;
  const count = rows.length;

  if (isLoading) {
    return (
      <div className="text-xs text-zinc-600 hidden md:block">오늘 ...</div>
    );
  }

  if (count === 0) {
    return (
      <div className="text-xs text-zinc-500 hidden md:block">오늘 종료 0건</div>
    );
  }

  return (
    <div
      className="text-xs hidden md:flex items-center gap-1.5"
      title={`오늘 종료 ${count}건의 합계 (수수료/세금 분리는 Phase 6)`}
    >
      <span className="text-zinc-500">오늘</span>
      <span className={`font-medium ${colorByPnL(totalProfit)}`}>
        {formatKRW(totalProfit)}
      </span>
      <span className={colorByPnL(aggregateRate)}>
        ({formatPct(aggregateRate)})
      </span>
    </div>
  );
}
