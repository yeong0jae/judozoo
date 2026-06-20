import { useRegimeDaily } from "../api/queries";
import RegimeFlowChart, { type FlowPoint } from "../components/market/RegimeFlowChart";
import { computeRows } from "../lib/regimeSegments";

const TIMES = ["08:15", "11:00", "14:00", "20:00"];

/**
 * 하루 4구간(직전 시점 대비 %)을 시간순으로 이어 누적 흐름선으로 만든다.
 * 구간은 전일20:00→08:15→11:00→14:00→20:00로 체인되므로 복리 누적하면 연속 레벨이 된다.
 * 아직 도달 안 한 시각(null)에서 멈춘다.
 */
function buildFlowPoints(chrono: ReturnType<typeof computeRows>): FlowPoint[] {
  const pts: FlowPoint[] = [];
  let index = 100;
  for (const row of chrono) {
    for (let i = 0; i < 4; i++) {
      const seg = row.segs[i];
      if (seg === null || seg === undefined) return pts; // 미도달 → 종료
      index *= 1 + seg / 100;
      pts.push({
        date: row.date,
        time: TIMES[i],
        cum: (index / 100 - 1) * 100,
        dayStart: i === 0,
      });
    }
  }
  return pts;
}

export default function MarketFlowPage() {
  const dailyQ = useRegimeDaily();
  const records = dailyQ.data ?? [];
  const chrono = [...records].reverse(); // 최신순 → 시간순(오래된 게 왼쪽)
  const points = buildFlowPoints(computeRows(chrono));
  const latest = points.length > 0 ? points[points.length - 1].cum : null;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-bold text-zinc-100">시장 흐름</h1>
        {latest !== null && (
          <p className="mt-1 text-sm text-zinc-400">
            누적{" "}
            <b className={latest >= 0 ? "text-red-400" : "text-blue-400"}>
              {latest >= 0 ? "+" : ""}
              {latest.toFixed(2)}%
            </b>
            <span className="ml-2 text-xs text-zinc-600">
              최근 20일 · 거래대금 상위 30종목(ETF 제외) 등락률 기준
            </span>
          </p>
        )}
      </div>

      <div className="rounded-xl border border-zinc-800 bg-zinc-900/60 p-4">
        {points.length === 0 ? (
          <div className="py-10 text-center text-sm text-zinc-600">데이터 누적 중…</div>
        ) : (
          <RegimeFlowChart points={points} />
        )}
      </div>
    </div>
  );
}
