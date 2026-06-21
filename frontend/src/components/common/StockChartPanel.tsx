import { useState } from "react";
import { useDailyCandles, useMinuteCandles } from "../../api/queries";
import CandleChart, { dailySeries, minuteSeries } from "./CandleChart";
import Skeleton from "./Skeleton";

type Tab = "minute" | "daily";

/**
 * 선택 종목 차트 — 1분봉/일봉 토글. 후보·돌파·스파이크·시그널 로그 공용.
 * 일봉(ka10081)은 30s 캐시라 상세 필터/RVOL 조회분과 공유.
 */
export default function StockChartPanel({
  stockCode,
  stockName,
  heightClass = "h-[32rem]",
}: {
  stockCode: string | null;
  stockName?: string;
  heightClass?: string;
}) {
  const [tab, setTab] = useState<Tab>("minute");
  const minuteQ = useMinuteCandles(tab === "minute" ? stockCode : null);
  const dailyQ = useDailyCandles(tab === "daily" ? stockCode : null);

  const minuteData = minuteQ.data;
  const dailyData = dailyQ.data;
  const loading = tab === "minute" ? minuteQ.isLoading : dailyQ.isLoading;
  const hasData =
    tab === "minute" ? (minuteData?.length ?? 0) > 0 : (dailyData?.length ?? 0) > 0;

  return (
    <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
      <div className="px-4 py-3 border-b border-white/[0.04] flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-zinc-200">{stockName ?? "차트"}</h3>
        <div className="flex rounded-lg bg-white/[0.04] p-0.5 text-xs">
          {(["minute", "daily"] as Tab[]).map((t) => (
            <button
              key={t}
              type="button"
              onClick={() => setTab(t)}
              className={`px-2.5 py-1 rounded-md transition-colors ${
                tab === t ? "bg-white/[0.1] text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
              }`}
            >
              {t === "minute" ? "1분봉" : "일봉"}
            </button>
          ))}
        </div>
      </div>
      <div className="p-4">
        {!stockCode ? (
          <div className={`${heightClass} flex items-center justify-center text-sm text-zinc-600`}>
            종목을 선택하면 차트가 표시됩니다
          </div>
        ) : loading ? (
          <Skeleton className={`${heightClass} w-full`} />
        ) : !hasData ? (
          <div className={`${heightClass} flex items-center justify-center text-sm text-zinc-600`}>
            차트 데이터가 없습니다
          </div>
        ) : tab === "minute" ? (
          <CandleChart
            key={`${stockCode}-m`}
            series={minuteSeries(minuteData!)}
            className={`w-full ${heightClass}`}
          />
        ) : (
          <CandleChart
            key={`${stockCode}-d`}
            series={dailySeries(dailyData!)}
            timeVisible={false}
            className={`w-full ${heightClass}`}
          />
        )}
      </div>
    </section>
  );
}
