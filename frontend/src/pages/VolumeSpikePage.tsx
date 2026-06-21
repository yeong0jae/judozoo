import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useVolumeSpikes } from "../api/queries";
import type { VolumeSpikeItem } from "../types";
import { formatKoreanMoney, formatPct, formatRelative } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import NumWon from "../components/common/NumWon";
import StockAvatar from "../components/common/StockAvatar";
import MinuteChartPanel from "../components/common/MinuteChartPanel";
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

  // 우측 차트에 띄울 선택 종목 — 첫 로드 시 1위 자동 선택
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  useEffect(() => {
    if (selectedCode === null && stocks.length > 0) setSelectedCode(stocks[0].stockCode);
  }, [stocks, selectedCode]);
  const selected = stocks.find((s) => s.stockCode === selectedCode);

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

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 items-start">
      <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
        {/* 등락률 임계값 선택 — 후보 풀 조절 */}
        <div className="flex justify-end px-4 py-2.5 border-b border-white/[0.04]">
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
          <>
          <div className="hidden md:block overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-zinc-500 text-xs">
                <tr>
                  <th className="px-4 py-2.5 text-left">종목</th>
                  <th className="px-4 py-2.5 text-right">배율</th>
                  <th className="px-4 py-2.5 text-right">1분 거래대금</th>
                  <th className="px-4 py-2.5 text-right">현재가</th>
                  <th className="px-4 py-2.5 text-right">등락률</th>
                </tr>
              </thead>
              <tbody>
                <AnimatePresence mode="popLayout">
                  {stocks.map((s) => (
                    <SpikeRow
                      key={s.stockCode}
                      s={s}
                      selected={s.stockCode === selectedCode}
                      onSelect={setSelectedCode}
                    />
                  ))}
                </AnimatePresence>
              </tbody>
            </table>
          </div>
          <div className="md:hidden">
            {stocks.map((s) => (
              <SpikeCard
                key={s.stockCode}
                s={s}
                selected={s.stockCode === selectedCode}
                onSelect={setSelectedCode}
              />
            ))}
          </div>
          </>
        )}
      </section>
      <div className={`lg:sticky lg:top-20 ${selectedCode ? "" : "hidden lg:block"}`}>
        <MinuteChartPanel stockCode={selectedCode} stockName={selected?.stockName} />
      </div>
      </div>
    </div>
  );
}

function SpikeRow({
  s,
  selected,
  onSelect,
}: {
  s: VolumeSpikeItem;
  selected: boolean;
  onSelect: (code: string) => void;
}) {
  const code = shortCode(s.stockCode);
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
      onClick={() => onSelect(s.stockCode)}
      className={`border-t border-white/[0.04] hover:bg-white/[0.03] transition-colors cursor-pointer ${
        selected ? "bg-emerald-900/40" : ""
      }`}
    >
      <td className="px-4 py-3.5">
        <div className="flex items-center gap-3">
          <StockAvatar name={s.stockName} code={code} />
          <div className="min-w-0">
            <div className="font-semibold text-zinc-100">{s.stockName}</div>
            <div className="text-xs text-zinc-500 num mt-0.5">{code}</div>
          </div>
        </div>
      </td>
      <td className={`px-4 py-3.5 text-right num font-semibold ${ratioColor(s.spikeRatio)}`}>
        {s.spikeRatio.toFixed(1)}배
      </td>
      <td className="px-4 py-3.5 text-right num text-zinc-400">
        {formatKoreanMoney(s.minuteTradingValue)}
      </td>
      <td className="px-4 py-3.5 text-right num font-medium text-zinc-100">
        <NumWon value={s.currentPrice} />
      </td>
      <td className="px-4 py-3.5 text-right">
        <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num font-medium" />
      </td>
    </motion.tr>
  );
}

/** 모바일 카드 — 배율을 우측 강조, 1분 거래대금·현재가·등락률을 압축. */
function SpikeCard({
  s,
  selected,
  onSelect,
}: {
  s: VolumeSpikeItem;
  selected: boolean;
  onSelect: (code: string) => void;
}) {
  const code = shortCode(s.stockCode);
  return (
    <div
      onClick={() => onSelect(s.stockCode)}
      className={`border-t border-white/[0.04] px-4 py-3.5 flex flex-col gap-1.5 cursor-pointer ${
        selected ? "bg-emerald-900/40" : ""
      }`}
    >
      <div className="flex items-center gap-2">
        <StockAvatar name={s.stockName} code={code} size={26} />
        <span className="font-semibold text-zinc-100 truncate flex-1 min-w-0">{s.stockName}</span>
        <span className={`num text-sm font-semibold shrink-0 ${ratioColor(s.spikeRatio)}`}>
          {s.spikeRatio.toFixed(1)}배
        </span>
      </div>
      <div className="flex items-baseline justify-between gap-2 pl-9">
        <span className="text-xs text-zinc-500 num truncate">
          {code} · 1분 {formatKoreanMoney(s.minuteTradingValue)}
        </span>
        <span className="flex items-baseline gap-2 shrink-0">
          <NumWon value={s.currentPrice} className="num text-sm font-medium text-zinc-100" />
          <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num text-xs" />
        </span>
      </div>
    </div>
  );
}
