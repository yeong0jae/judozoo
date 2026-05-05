import { useState } from "react";
import { mockDailyReport } from "../mocks/data";
import { colorByPnL, formatKrw, formatPct } from "../lib/format";

const dates = Array.from(
  new Set(mockDailyReport.map((r) => (r.closedAt ?? r.createdAt).slice(0, 10))),
)
  .sort()
  .reverse();

export default function ReportPage() {
  const [date, setDate] = useState(dates[0]);
  const rows = mockDailyReport.filter(
    (r) => (r.closedAt ?? r.createdAt).slice(0, 10) === date,
  );

  const totals = rows.reduce(
    (acc, r) => {
      acc.profit += r.profitAmount;
      acc.count += 1;
      return acc;
    },
    { profit: 0, count: 0 },
  );

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">일별 실적</h2>
        <select
          value={date}
          onChange={(e) => setDate(e.target.value)}
          className="bg-zinc-900 border border-zinc-800 rounded px-3 py-1.5 text-sm"
        >
          {dates.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
      </div>

      <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-zinc-950 text-xs uppercase text-zinc-500">
            <tr>
              <th className="text-left px-4 py-3">종료시각</th>
              <th className="text-left px-4 py-3">종목명</th>
              <th className="text-right px-4 py-3">수익금</th>
              <th className="text-right px-4 py-3">수익률</th>
              <th className="text-center px-4 py-3">청산 사유</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const isAnomaly =
                r.closeReason === "UNCLOSED" || r.closeReason === "NO_FILL";
              return (
                <tr
                  key={r.commandId}
                  className={`border-t border-zinc-800 ${
                    isAnomaly ? "bg-amber-950/20" : ""
                  }`}
                >
                  <td className="px-4 py-3 text-zinc-400">
                    {r.closedAt?.slice(11, 19) ?? "-"}
                  </td>
                  <td className="px-4 py-3 font-medium">
                    {r.stockName}
                    <span className="text-xs text-zinc-500 ml-2">
                      {r.stockCode}
                    </span>
                  </td>
                  <td
                    className={`px-4 py-3 text-right font-medium ${colorByPnL(r.profitAmount)}`}
                  >
                    {r.profitAmount === 0 ? "-" : formatKrw(r.profitAmount)}
                  </td>
                  <td
                    className={`px-4 py-3 text-right ${colorByPnL(r.profitRate)}`}
                  >
                    {r.profitRate === 0 ? "-" : formatPct(r.profitRate)}
                  </td>
                  <td className="px-4 py-3 text-center">
                    {r.closeReason && <CloseReasonBadge reason={r.closeReason} />}
                  </td>
                </tr>
              );
            })}
            {rows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-zinc-600">
                  해당 일자의 실적이 없습니다.
                </td>
              </tr>
            )}
          </tbody>
          {rows.length > 0 && (
            <tfoot className="bg-zinc-950 text-sm font-medium border-t border-zinc-800">
              <tr>
                <td colSpan={2} className="px-4 py-3 text-zinc-400">
                  합계 ({totals.count}건)
                </td>
                <td
                  className={`px-4 py-3 text-right ${colorByPnL(totals.profit)}`}
                >
                  {formatKrw(totals.profit)}
                </td>
                <td colSpan={2}></td>
              </tr>
            </tfoot>
          )}
        </table>
      </div>

      <p className="text-xs text-zinc-500">
        UNCLOSED / NO_FILL 행은 시스템 다운/재시작 또는 3회 매수 미체결로 인한
        미처리 명령입니다. 수수료/세금 분리 표시는 Phase 6에서 백엔드 집계
        제공 후 활성화됩니다.
      </p>
    </div>
  );
}

function CloseReasonBadge({ reason }: { reason: string }) {
  const danger = ["STOP_LOSS", "UNCLOSED", "NO_FILL", "MARKET_CLOSE"].includes(
    reason,
  );
  const success = ["TAKE_PROFIT", "TREND_BREAK"].includes(reason);
  const cls = danger
    ? "bg-rose-900/40 text-rose-300 border-rose-800"
    : success
      ? "bg-emerald-900/40 text-emerald-300 border-emerald-800"
      : "bg-zinc-800 text-zinc-300 border-zinc-700";
  return (
    <span className={`px-2 py-0.5 rounded text-xs border ${cls}`}>{reason}</span>
  );
}
