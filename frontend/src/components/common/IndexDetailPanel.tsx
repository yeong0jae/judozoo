import { useIndexMinuteCandles } from "../../api/queries";
import type { MarketType } from "../../types";
import CandleChart, { indexMinuteSeries } from "./CandleChart";
import EmptyState from "./EmptyState";
import Skeleton from "./Skeleton";

const MARKET_LABEL: Record<MarketType, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };

/** 지수(코스피/코스닥) 1분봉 차트 패널 — 실시간 로그에서 지수 행 선택 시 우측에. */
export default function IndexDetailPanel({
  market,
  date,
}: {
  market: MarketType | null;
  date: string;
}) {
  const candlesQ = useIndexMinuteCandles(market, date);
  const candles = candlesQ.data ?? [];

  if (!market) {
    return (
      <div className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
        <div className="h-[28rem] flex items-center justify-center text-sm text-zinc-600">
          행을 선택하면 표시됩니다
        </div>
      </div>
    );
  }

  return (
    <div className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden flex flex-col">
      <header className="px-4 sm:px-6 py-4 border-b border-white/[0.04]">
        <div className="flex items-center gap-2">
          <span className="text-base font-semibold">{MARKET_LABEL[market]} 지수</span>
          <span className="text-xs text-zinc-500">당일 1분봉</span>
        </div>
      </header>
      <div className="p-3">
        {candlesQ.isLoading ? (
          <Skeleton className="h-[28rem] w-full" />
        ) : candles.length === 0 ? (
          <div className="h-[28rem] flex items-center justify-center">
            <EmptyState message="장중에 지수 분봉이 표시됩니다" />
          </div>
        ) : (
          <CandleChart series={indexMinuteSeries(candles)} priceDecimals={2} className="w-full h-[28rem]" />
        )}
      </div>
    </div>
  );
}
