import { mockTodayClosed } from "../../mocks/data";
import { colorByPnL, formatKRW, formatPct } from "../../lib/format";

// Phase 5-B-2에서 useTodayClosed()로 교체.
// ※ profitAmount는 백엔드 DailyTradingResult 기준 (수수료/세금 분리 없음 — Phase 6에서 정확화).
export default function TodayProfitSummary() {
  const totalProfit = mockTodayClosed.reduce(
    (sum, r) => sum + r.profitAmount,
    0,
  );
  const totalBase = mockTodayClosed.reduce(
    (sum, r) => sum + (r.profitAmount === 0 ? 0 : Math.abs(r.profitAmount / Math.max(r.profitRate, 0.0001))),
    0,
  );
  const aggregateRate = totalBase > 0 ? totalProfit / totalBase : 0;
  const count = mockTodayClosed.length;

  if (count === 0) {
    return (
      <div className="text-xs text-zinc-500 hidden md:block">
        오늘 종료 0건
      </div>
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
      <span className={colorByPnL(aggregateRate)}>({formatPct(aggregateRate)})</span>
    </div>
  );
}
