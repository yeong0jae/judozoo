import { useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useSignalBoard } from "../api/queries";
import type { SignalBoardItem } from "../types";
import { formatKoreanMoney, formatPct, formatRelative } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import NumWon from "../components/common/NumWon";
import StockAvatar from "../components/common/StockAvatar";
import ChangeRateSelector, {
  CHANGE_RATE_OPTIONS,
} from "../components/common/ChangeRateSelector";

const MIN_CHANGE_RATE_KEY = "signalBoard.minChangeRate";

/** 키움 마스터 코드 — "009150_AL" 같이 거래소 접미사가 붙으면 앞쪽 6자리만. */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

/** 켜진 신호 수 → 교차 강도 배지 */
function tierBadge(count: number): { label: string; cls: string } {
  if (count >= 2) return { label: "강", cls: "bg-emerald-500/15 text-emerald-400" };
  if (count === 1) return { label: "중", cls: "bg-amber-500/15 text-amber-400" };
  return { label: "약", cls: "bg-zinc-700/40 text-zinc-500" };
}

/** 돌파까지 남은 % → 돌파 칩 라벨 (활성 신호만 들어오므로 갭 < 2%) */
function breakoutChip(gap: number): { label: string; cls: string } {
  if (gap <= 0) return { label: "돌파", cls: "bg-emerald-500/15 text-emerald-400" };
  if (gap < 1) return { label: `임박 ${gap.toFixed(2)}%`, cls: "bg-amber-500/20 text-amber-300" };
  return { label: `근접 ${gap.toFixed(2)}%`, cls: "bg-amber-500/10 text-amber-400" };
}

export default function SignalBoardPage() {
  // 등락률 임계값 — 새로고침해도 유지(다른 페이지와 별개 키), 기본 7%.
  const [minChangeRate, setMinChangeRate] = useState(() => {
    const raw = localStorage.getItem(MIN_CHANGE_RATE_KEY);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : 7;
  });
  const setRate = (r: number) => {
    setMinChangeRate(r);
    localStorage.setItem(MIN_CHANGE_RATE_KEY, String(r));
  };

  const boardQ = useSignalBoard(minChangeRate);
  const data = boardQ.data;
  const stocks = data?.stocks ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
        <div>
          <h2 className="text-xl font-bold flex items-center gap-2">
            종합 시그널
            <span
              className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
                boardQ.isFetching ? "animate-ping" : "animate-pulse"
              }`}
            />
          </h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            주도주 후보의 돌파·스파이크를 교차 · 둘 다 켜진 종목이 위로 · 5초 자동 갱신
          </p>
        </div>
        <div className="text-xs text-zinc-500 flex items-center gap-2">
          {data?.queriedAt && <span>조회 {formatRelative(data.queriedAt)}</span>}
          {typeof data?.totalCount === "number" && (
            <span className="text-zinc-300 font-medium">{data.totalCount}건</span>
          )}
        </div>
      </div>

      <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
        {/* 등락률 임계값 선택 — 후보 풀 조절 */}
        <div className="flex justify-end px-4 py-2.5 border-b border-white/[0.04]">
          <ChangeRateSelector value={minChangeRate} onChange={setRate} />
        </div>
        {boardQ.isLoading ? (
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
              <thead className="text-zinc-500 text-xs">
                <tr>
                  <th className="px-4 py-2.5 text-left">종목</th>
                  <th className="px-4 py-2.5 text-left">신호</th>
                  <th className="px-4 py-2.5 text-right">현재가</th>
                  <th className="px-4 py-2.5 text-right">등락률</th>
                  <th className="px-4 py-2.5 text-right">거래대금</th>
                </tr>
              </thead>
              <tbody>
                <AnimatePresence mode="popLayout">
                  {stocks.map((s) => (
                    <SignalRow key={s.stockCode} s={s} />
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

function SignalRow({ s }: { s: SignalBoardItem }) {
  const code = shortCode(s.stockCode);
  const tier = tierBadge(s.signalCount);
  const bo = s.breakout ? breakoutChip(s.breakout.gapRate) : null;
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
      className={`border-t border-white/[0.04] hover:bg-white/[0.03] transition-colors ${
        s.signalCount >= 2 ? "bg-emerald-500/[0.04]" : ""
      }`}
    >
      <td className="px-4 py-3.5">
        <div className="flex items-center gap-3">
          <StockAvatar name={s.stockName} code={code} />
          <div className="min-w-0">
            <div className="flex items-center flex-wrap gap-1.5">
              <span className="font-semibold text-zinc-100">{s.stockName}</span>
              {s.themes.map((t) => (
                <span
                  key={t}
                  className="text-[11px] px-2 py-0.5 rounded-full bg-white/[0.06] text-zinc-400"
                >
                  {t}
                </span>
              ))}
              {s.themeCount > s.themes.length && (
                <span className="text-[11px] text-zinc-500">+{s.themeCount - s.themes.length}</span>
              )}
            </div>
            <div className="text-xs text-zinc-500 num mt-0.5">{code}</div>
          </div>
        </div>
      </td>
      <td className="px-4 py-3.5">
        <div className="flex items-center flex-wrap gap-1.5">
          <span className={`text-xs font-bold px-1.5 py-0.5 rounded ${tier.cls}`}>{tier.label}</span>
          {bo && (
            <span className={`text-xs font-medium px-1.5 py-0.5 rounded num ${bo.cls}`}>
              {bo.label}
            </span>
          )}
          {s.spike && (
            <span className="text-xs font-medium px-1.5 py-0.5 rounded num bg-rose-500/15 text-rose-300">
              🔥{s.spike.spikeRatio.toFixed(1)}배
            </span>
          )}
          {s.signalCount === 0 && <span className="text-xs text-zinc-500">주도주</span>}
        </div>
      </td>
      <td className="px-4 py-3.5 text-right num font-medium text-zinc-100">
        <NumWon value={s.currentPrice} />
      </td>
      <td className="px-4 py-3.5 text-right">
        <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num font-medium" />
      </td>
      <td className="px-4 py-3.5 text-right num text-zinc-400">
        {formatKoreanMoney(s.tradingValue)}
      </td>
    </motion.tr>
  );
}
