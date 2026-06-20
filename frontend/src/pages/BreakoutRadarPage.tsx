import { useBreakoutRadar } from "../api/queries";
import type { BreakoutRadarItem } from "../types";
import { formatPrice, formatRelative } from "../lib/format";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";

/** 키움 마스터 코드 — "009150_AL" 같이 거래소 접미사가 붙으면 앞쪽 6자리만. */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

/** 돌파까지 남은 % → 임박도 라벨/색 */
function radarStatus(gap: number): { label: string; cls: string; gap: string } {
  if (gap <= 0)
    return { label: "돌파", cls: "bg-emerald-500/15 text-emerald-400", gap: "text-emerald-400" };
  if (gap < 1)
    return { label: "임박", cls: "bg-amber-500/20 text-amber-300", gap: "text-amber-300" };
  if (gap < 2)
    return { label: "근접", cls: "bg-amber-500/15 text-amber-400", gap: "text-amber-400" };
  return { label: "관망", cls: "bg-zinc-700/40 text-zinc-400", gap: "text-zinc-300" };
}

export default function BreakoutRadarPage() {
  const radarQ = useBreakoutRadar();
  const data = radarQ.data;
  const stocks = data?.stocks ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
        <div>
          <h2 className="text-xl font-bold flex items-center gap-2">
            돌파 임박 레이더
            <span
              className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
                radarQ.isFetching ? "animate-ping" : "animate-pulse"
              }`}
            />
          </h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            주도주 후보를 당일 고가 돌파에 가까운 순으로 · 5초 자동 갱신
          </p>
        </div>
        <div className="text-xs text-zinc-500 flex items-center gap-2">
          {data?.queriedAt && <span>조회 {formatRelative(data.queriedAt)}</span>}
          {typeof data?.totalCount === "number" && (
            <span className="text-zinc-300 font-medium">{data.totalCount}건</span>
          )}
        </div>
      </div>

      <section className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden">
        {radarQ.isLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : stocks.length === 0 ? (
          <EmptyState message="후보 종목이 없습니다" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-zinc-950 text-zinc-500 text-xs">
                <tr>
                  <th className="px-4 py-2.5 text-left">종목</th>
                  <th className="px-4 py-2.5 text-right">현재가</th>
                  <th className="px-4 py-2.5 text-right">돌파선</th>
                  <th className="px-4 py-2.5 text-right">돌파까지</th>
                  <th className="px-4 py-2.5 text-right">상태</th>
                </tr>
              </thead>
              <tbody>
                {stocks.map((s) => (
                  <RadarRow key={s.stockCode} s={s} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function RadarRow({ s }: { s: BreakoutRadarItem }) {
  const code = shortCode(s.stockCode);
  const st = radarStatus(s.gapRate);
  const gapWon = s.dayHigh - s.currentPrice;
  return (
    <tr className="border-t border-zinc-800 hover:bg-zinc-800/40">
      <td className="px-4 py-3">
        <div className="flex items-center flex-wrap gap-2">
          <span className="font-medium">{s.stockName}</span>
          {s.themes.map((t) => (
            <span
              key={t}
              className="text-[11px] px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-200 border border-zinc-700"
            >
              {t}
            </span>
          ))}
          {s.themeCount > s.themes.length && (
            <span className="text-[11px] text-zinc-500">+{s.themeCount - s.themes.length}</span>
          )}
        </div>
        <div className="text-xs text-zinc-500 num">{code}</div>
      </td>
      <td className="px-4 py-3 text-right num text-zinc-300">{formatPrice(s.currentPrice)}</td>
      <td className="px-4 py-3 text-right num text-zinc-300">{formatPrice(s.dayHigh)}</td>
      <td className={`px-4 py-3 text-right num font-semibold ${st.gap}`}>
        {s.gapRate <= 0 ? "돌파" : `${formatPrice(gapWon)}원 (${s.gapRate.toFixed(2)}%)`}
      </td>
      <td className="px-4 py-3 text-right">
        <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${st.cls}`}>{st.label}</span>
      </td>
    </tr>
  );
}
