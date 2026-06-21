import { useMinuteCandles } from "../../api/queries";
import CandleChart, { minuteSeries } from "./CandleChart";
import Skeleton from "./Skeleton";

/**
 * 2분할 화면 우측 — 선택된 종목의 1분봉 차트.
 * stockCode가 null이면 안내 문구, 있으면 해당 종목 차트(30초 갱신)를 보여준다.
 */
export default function MinuteChartPanel({
  stockCode,
  stockName,
}: {
  stockCode: string | null;
  stockName?: string;
}) {
  const { data, isLoading } = useMinuteCandles(stockCode);
  const H = "h-[28rem]";
  return (
    <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
      <div className="px-4 py-3 border-b border-white/[0.04]">
        <h3 className="text-sm font-semibold text-zinc-200">
          {stockName ? `${stockName} · 1분봉` : "1분봉"}
        </h3>
      </div>
      <div className="p-4">
        {!stockCode ? (
          <div className={`${H} flex items-center justify-center text-sm text-zinc-600`}>
            종목을 선택하면 차트가 표시됩니다
          </div>
        ) : isLoading ? (
          <Skeleton className={`${H} w-full`} />
        ) : !data || data.length === 0 ? (
          <div className={`${H} flex items-center justify-center text-sm text-zinc-600`}>
            분봉 데이터가 없습니다
          </div>
        ) : (
          <CandleChart key={stockCode} series={minuteSeries(data)} className={`w-full ${H}`} />
        )}
      </div>
    </section>
  );
}
