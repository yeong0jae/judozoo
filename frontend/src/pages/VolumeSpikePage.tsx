import { useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useVolumeSpikes } from "../api/queries";
import type { VolumeSpikeItem } from "../types";
import { formatKoreanMoney, formatPct, formatRelative } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import NumWon from "../components/common/NumWon";
import ChangeRateSelector, {
  CHANGE_RATE_OPTIONS,
} from "../components/common/ChangeRateSelector";

const MIN_CHANGE_RATE_KEY = "volumeSpike.minChangeRate";

function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

/** 배율이 클수록 강한 색 */
function ratioColor(r: number): string {
  if (r >= 8) return "text-rose-400";
  if (r >= 5) return "text-amber-300";
  return "text-amber-400";
}

export default function VolumeSpikePage() {
  const [minChangeRate, setMinChangeRate] = useState(() => {
    const raw = localStorage.getItem(MIN_CHANGE_RATE_KEY);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : 7;
  });
  const setRate = (r: number) => {
    setMinChangeRate(r);
    localStorage.setItem(MIN_CHANGE_RATE_KEY, String(r));
  };

  const spikeQ = useVolumeSpikes(minChangeRate);
  const data = spikeQ.data;
  const stocks = data?.stocks ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
        <div>
          <h2 className="text-xl font-bold flex items-center gap-2">
            거래대금 스파이크
            <span
              className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
                spikeQ.isFetching ? "animate-ping" : "animate-pulse"
              }`}
            />
          </h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            주도주 후보 중 최신 1분봉 거래대금이 직전 평균 대비 급증한 종목 · 5초 자동 갱신
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
        {/* 등락률 임계값 선택 — 후보 풀 조절 */}
        <div className="flex justify-end px-4 py-2 border-b border-zinc-800">
          <ChangeRateSelector value={minChangeRate} onChange={setRate} />
        </div>
        {spikeQ.isLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : stocks.length === 0 ? (
          <EmptyState message="스파이크 종목이 없습니다" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-zinc-950 text-zinc-500 text-xs">
                <tr>
                  <th className="px-4 py-2.5 text-left">종목</th>
                  <th className="px-4 py-2.5 text-right">현재가</th>
                  <th className="px-4 py-2.5 text-right">등락률</th>
                  <th className="px-4 py-2.5 text-right">1분 거래대금</th>
                  <th className="px-4 py-2.5 text-right">배율</th>
                  <th className="px-4 py-2.5 text-right">시각</th>
                </tr>
              </thead>
              <tbody>
                <AnimatePresence mode="popLayout">
                  {stocks.map((s) => (
                    <SpikeRow key={s.stockCode} s={s} />
                  ))}
                </AnimatePresence>
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function SpikeRow({ s }: { s: VolumeSpikeItem }) {
  const code = shortCode(s.stockCode);
  // 키움 분봉 cntr_tm이 HTS보다 1분 이르게 라벨링됨 — HTS 기준 +1분 보정
  const t = new Date(s.at);
  t.setMinutes(t.getMinutes() + 1);
  const time = t.toTimeString().slice(0, 5);
  return (
    <motion.tr
      layout
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{
        layout: { type: "spring", stiffness: 600, damping: 42 },
        opacity: { duration: 0.2 },
      }}
      className="border-t border-zinc-800 hover:bg-zinc-800/40"
    >
      <td className="px-4 py-3">
        <div className="font-medium">{s.stockName}</div>
        <div className="text-xs text-zinc-500 num">{code}</div>
      </td>
      <td className="px-4 py-3 text-right num text-zinc-300">
        <NumWon value={s.currentPrice} />
      </td>
      <td className="px-4 py-3 text-right">
        <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num" />
      </td>
      <td className="px-4 py-3 text-right num text-zinc-300">
        {formatKoreanMoney(s.minuteTradingValue)}
      </td>
      <td className={`px-4 py-3 text-right num font-semibold ${ratioColor(s.spikeRatio)}`}>
        {s.spikeRatio.toFixed(1)}배
      </td>
      <td className="px-4 py-3 text-right num text-zinc-500">{time}</td>
    </motion.tr>
  );
}
