import { useIndexMinuteCandles } from "../../api/queries";
import type { MarketType } from "../../types";
import { formatPct } from "../../lib/format";
import IndexLineChart from "./IndexLineChart";
import ProfitText from "./ProfitText";
import EmptyState from "./EmptyState";
import Skeleton from "./Skeleton";

const MARKET_LABEL: Record<MarketType, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };

/** 지수값 → 천 단위 쉼표 + 소수 2자리. */
const fmtIndex = (v: number) =>
  v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** 지수(코스피/코스닥) 1분봉 차트 패널 — 실시간 로그에서 지수 행 선택 시 우측에. */
export default function IndexDetailPanel({
  market,
  date,
  changeRate,
}: {
  market: MarketType | null;
  date: string;
  changeRate?: number | null;
}) {
  const candlesQ = useIndexMinuteCandles(market, date);
  const candles = candlesQ.data ?? [];
  const lastValue = candles.length > 0 ? candles[candles.length - 1].close : null;

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
          <span className="text-xs text-zinc-500">1분 추이</span>
        </div>
        {lastValue != null && (
          <div className="text-xs text-zinc-400 mt-0.5">
            <span className="num">{fmtIndex(lastValue)}</span>
            {changeRate != null && (
              <ProfitText value={changeRate / 100} format={formatPct} className="num ml-1.5" />
            )}
          </div>
        )}
      </header>
      <div className="p-3">
        {candlesQ.isLoading ? (
          <Skeleton className="h-[28rem] w-full" />
        ) : candles.length === 0 ? (
          <div className="h-[28rem] flex items-center justify-center">
            <EmptyState message="장중에 지수 분봉이 표시됩니다" />
          </div>
        ) : (
          <IndexLineChart items={candles} changeRate={changeRate} className="w-full h-[28rem]" />
        )}
      </div>
    </div>
  );
}
