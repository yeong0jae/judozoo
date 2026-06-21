import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useBreakoutRadar } from "../api/queries";
import type { BreakoutRadarItem } from "../types";
import { formatKoreanMoney, formatPct, formatPrice, formatRelative } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import NumWon from "../components/common/NumWon";
import StockAvatar from "../components/common/StockAvatar";
import StockDetailPanel from "../components/common/StockDetailPanel";
import ChangeRateSelector, {
  CHANGE_RATE_OPTIONS,
} from "../components/common/ChangeRateSelector";

const MIN_CHANGE_RATE_KEY = "breakoutRadar.minChangeRate";

/** 키움 마스터 코드 — "009150_AL" 같이 거래소 접미사가 붙으면 앞쪽 6자리만. */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

/** 돌파까지 남은 % → 임박도 라벨/색 */
function radarStatus(gap: number): { label: string; cls: string; gap: string } {
  if (gap <= 0)
    return { label: "돌파", cls: "bg-emerald-500/15 text-emerald-400", gap: "text-emerald-400" };
  if (gap < 2)
    return { label: "임박", cls: "bg-amber-500/20 text-amber-300", gap: "text-amber-300" };
  if (gap < 4)
    return { label: "주시", cls: "bg-amber-500/15 text-amber-400", gap: "text-amber-400" };
  return { label: "관망", cls: "bg-zinc-700/40 text-zinc-400", gap: "text-zinc-300" };
}

export default function BreakoutRadarPage() {
  // 등락률 임계값 — 새로고침해도 유지(라디오 풀은 주도주와 별개 키), 기본 7%.
  const [minChangeRate, setMinChangeRate] = useState(() => {
    const raw = localStorage.getItem(MIN_CHANGE_RATE_KEY);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : 7;
  });
  const setRate = (r: number) => {
    setMinChangeRate(r);
    localStorage.setItem(MIN_CHANGE_RATE_KEY, String(r));
  };

  const radarQ = useBreakoutRadar(minChangeRate);
  const data = radarQ.data;
  const stocks = data?.stocks ?? [];

  // 우측 차트에 띄울 선택 종목 — 첫 로드 시 1위 자동 선택
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  useEffect(() => {
    if (selectedCode === null && stocks.length > 0) setSelectedCode(stocks[0].stockCode);
  }, [stocks, selectedCode]);

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
        <div>
          <h2 className="text-xl font-bold flex items-center gap-2">
            돌파 임박 시그널
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

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 items-start">
      <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
        {/* 등락률 임계값 선택 — 후보 풀 조절 */}
        <div className="flex justify-end px-4 py-2.5 border-b border-white/[0.04]">
          <ChangeRateSelector value={minChangeRate} onChange={setRate} />
        </div>
        {radarQ.isLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : stocks.length === 0 ? (
          <EmptyState message="후보 종목이 없습니다" />
        ) : (
          <>
          <div className="hidden md:block overflow-x-auto">
            <table className="w-full text-xs">
              <thead className="text-zinc-500 text-xs">
                <tr>
                  <th className="px-4 py-2.5 text-left">종목</th>
                  <th className="px-4 py-2.5 text-right">돌파선</th>
                  <th className="px-4 py-2.5 text-right">돌파까지</th>
                  <th className="px-4 py-2.5 text-right">상태</th>
                  <th className="px-4 py-2.5 text-right">거래대금</th>
                  <th className="px-4 py-2.5 text-right">현재가</th>
                  <th className="px-4 py-2.5 text-right">등락률</th>
                </tr>
              </thead>
              <tbody>
                <AnimatePresence mode="popLayout">
                  {stocks.map((s) => (
                    <RadarRow
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
              <RadarCard
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
        <StockDetailPanel stockCode={selectedCode} defaultTab="minute" />
      </div>
      </div>
    </div>
  );
}

function RadarRow({
  s,
  selected,
  onSelect,
}: {
  s: BreakoutRadarItem;
  selected: boolean;
  onSelect: (code: string) => void;
}) {
  const code = shortCode(s.stockCode);
  const st = radarStatus(s.gapRate);
  const gapWon = s.dayHigh - s.currentPrice;
  // 키움 분봉 cntr_tm이 HTS보다 1분 이르게 라벨링됨 — HTS 기준 +1분 보정
  const peak = new Date(s.peakAt);
  peak.setMinutes(peak.getMinutes() + 1);
  const peakTime = peak.toTimeString().slice(0, 5);
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
      <td className="px-4 py-3.5 text-right">
        <div className="num text-zinc-300">{formatPrice(s.dayHigh)}</div>
        <div className="num text-xs text-zinc-500">{peak.getDate()}일 {peakTime} 형성</div>
      </td>
      <td className={`px-4 py-3.5 text-right num font-semibold ${st.gap}`}>
        {s.gapRate <= 0 ? "돌파" : `${formatPrice(gapWon)}원 (${s.gapRate.toFixed(2)}%)`}
      </td>
      <td className="px-4 py-3.5 text-right">
        <motion.span
          key={st.label}
          initial={{ scale: 1.25 }}
          animate={{ scale: 1 }}
          transition={{ type: "spring", stiffness: 500, damping: 18 }}
          className={`inline-block text-xs font-medium px-1.5 py-0.5 rounded ${st.cls}`}
        >
          {st.label}
        </motion.span>
      </td>
      <td className="px-4 py-3.5 text-right num text-zinc-400">
        {formatKoreanMoney(s.tradingValue)}
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

/** 모바일 카드 — 표 컬럼을 3행으로 압축 (돌파선·돌파까지·상태 우선). */
function RadarCard({
  s,
  selected,
  onSelect,
}: {
  s: BreakoutRadarItem;
  selected: boolean;
  onSelect: (code: string) => void;
}) {
  const code = shortCode(s.stockCode);
  const st = radarStatus(s.gapRate);
  const gapWon = s.dayHigh - s.currentPrice;
  const peak = new Date(s.peakAt);
  peak.setMinutes(peak.getMinutes() + 1);
  const peakTime = peak.toTimeString().slice(0, 5);
  return (
    <div
      onClick={() => onSelect(s.stockCode)}
      className={`border-t border-white/[0.04] px-4 py-3.5 flex flex-col gap-1.5 cursor-pointer ${
        selected ? "bg-emerald-900/40" : ""
      }`}
    >
      {/* 1행: 종목 · 상태 */}
      <div className="flex items-center gap-2">
        <StockAvatar name={s.stockName} code={code} size={26} />
        <span className="font-semibold text-zinc-100 truncate flex-1 min-w-0">{s.stockName}</span>
        <span className={`text-xs font-medium px-1.5 py-0.5 rounded shrink-0 ${st.cls}`}>{st.label}</span>
      </div>
      {/* 2행: 돌파선 · 돌파까지 */}
      <div className="flex items-baseline justify-between gap-2 pl-9">
        <span className="text-xs text-zinc-500 num">
          돌파선 {formatPrice(s.dayHigh)} · {peak.getDate()}일 {peakTime}
        </span>
        <span className={`num text-sm font-semibold ${st.gap}`}>
          {s.gapRate <= 0 ? "돌파" : `${formatPrice(gapWon)}원 (${s.gapRate.toFixed(2)}%)`}
        </span>
      </div>
      {/* 3행: 코드·거래대금 · 현재가·등락률 */}
      <div className="flex items-baseline justify-between gap-2 pl-9">
        <span className="text-xs text-zinc-500 num truncate">
          {code} · {formatKoreanMoney(s.tradingValue)}
        </span>
        <span className="flex items-baseline gap-2 shrink-0">
          <NumWon value={s.currentPrice} className="num text-sm font-medium text-zinc-100" />
          <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num text-xs" />
        </span>
      </div>
    </div>
  );
}
