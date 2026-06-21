import { useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useSignalEvents } from "../api/queries";
import type { SignalEventItem, SignalEventType } from "../types";
import { formatKoreanMoney, formatPct, formatPrice } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import StockAvatar from "../components/common/StockAvatar";

/** 키움 마스터 코드 — "009150_AL" 같이 거래소 접미사가 붙으면 앞쪽 6자리만. */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

/** ISO LocalDateTime → HH:mm:ss (타임존 변환 없이 문자열에서 직접). */
function clockOf(iso: string): string {
  return iso.slice(11, 19);
}

const EVENT_META: Record<SignalEventType, { label: string; chip: string; dot: string }> = {
  BREAKOUT: { label: "돌파", chip: "bg-emerald-500/15 text-emerald-400", dot: "bg-emerald-400" },
  BREAKOUT_IMMINENT: { label: "임박", chip: "bg-amber-500/20 text-amber-300", dot: "bg-amber-300" },
  VOLUME_SPIKE: { label: "스파이크", chip: "bg-rose-500/15 text-rose-300", dot: "bg-rose-400" },
};

/** 이벤트별 핵심 수치 한 줄. */
function detailOf(e: SignalEventItem): string {
  if (e.eventType === "VOLUME_SPIKE") return e.spikeRatio ? `🔥${e.spikeRatio.toFixed(1)}배` : "";
  if (e.eventType === "BREAKOUT") return "전고 돌파";
  return e.gapRate != null ? `${e.gapRate.toFixed(2)}% 남음` : "";
}

// TEMP: 주말 미리보기용 목 데이터 — 확인 후 이 블록과 아래 사용처를 제거할 것
export default function SignalLogPage() {
  const eventsQ = useSignalEvents();
  const data = eventsQ.data;
  const events = data?.events ?? [];
  const [openKey, setOpenKey] = useState<string | null>(null);

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
        <div>
          <h2 className="text-xl font-bold flex items-center gap-2">
            시그널 로그
            <span
              className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
                eventsQ.isFetching ? "animate-ping" : "animate-pulse"
              }`}
            />
          </h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            오늘 발생한 돌파·임박·스파이크 전이를 발생순으로 · 행을 누르면 그 종목의 하루 여정 · 5초 갱신
          </p>
        </div>
        <div className="text-xs text-zinc-500">
          {typeof data?.totalCount === "number" && (
            <span className="text-zinc-300 font-medium">{data.totalCount}건</span>
          )}
        </div>
      </div>

      <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
        {eventsQ.isLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : events.length === 0 ? (
          <EmptyState message="오늘 발생한 시그널이 없습니다" />
        ) : (
          <ul className="divide-y divide-white/[0.04]">
            <AnimatePresence initial={false}>
              {events.map((e, i) => {
                const code = shortCode(e.stockCode);
                const meta = EVENT_META[e.eventType];
                const rowKey = `${e.stockCode}-${e.eventType}-${e.occurredAt}-${i}`;
                const open = openKey === rowKey;
                // 같은 종목 이벤트 모음(피드는 최신순) → 여정은 오래된 순, 누적 거래대금은 최신 스냅샷
                const stockEvents = open ? events.filter((x) => x.stockCode === e.stockCode) : [];
                const journey = [...stockEvents].reverse();
                return (
                  <motion.li
                    key={rowKey}
                    layout
                    initial={{ opacity: 0, y: -6 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0 }}
                    transition={{ duration: 0.2 }}
                  >
                    <button
                      type="button"
                      onClick={() => setOpenKey(open ? null : rowKey)}
                      className="w-full flex items-center flex-wrap gap-x-3 gap-y-1 px-4 py-3 text-left hover:bg-white/[0.03] transition-colors"
                    >
                      {/* 왼쪽: 시각·유형·종목 */}
                      <div className="flex items-center gap-2 min-w-0 flex-1">
                        <span className="num text-xs text-zinc-500 tabular-nums w-16 shrink-0">
                          {clockOf(e.occurredAt)}
                        </span>
                        <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${meta.dot}`} />
                        <span className={`text-xs font-semibold px-1.5 py-0.5 rounded shrink-0 ${meta.chip}`}>
                          {meta.label}
                        </span>
                        <StockAvatar name={e.stockName} code={code} />
                        <span className="font-semibold text-zinc-100 truncate">{e.stockName}</span>
                        {e.theme && (
                          <span className="text-[11px] px-2 py-0.5 rounded-full bg-white/[0.06] text-zinc-400 shrink-0">
                            {e.theme}
                          </span>
                        )}
                      </div>
                      {/* 오른쪽: 디테일·현재가·등락률 (모바일에선 아래 줄로 래핑) */}
                      <div className="flex items-center gap-3 shrink-0 ml-auto pl-[4.5rem] md:pl-0">
                        <span className="num text-xs text-zinc-300">{detailOf(e)}</span>
                        <span className="num text-sm text-zinc-100 w-20 text-right">
                          {formatPrice(e.currentPrice)}
                        </span>
                        <span className="w-16 text-right">
                          <ProfitText
                            value={e.priceChangeRate / 100}
                            format={formatPct}
                            className="num text-xs"
                          />
                        </span>
                      </div>
                    </button>

                    {open && (
                      <div className="px-4 pb-3 pt-1 bg-white/[0.02]">
                        <div className="text-xs text-zinc-500 mb-2">
                          {e.stockName} 오늘 여정 · 누적 거래대금 {formatKoreanMoney(stockEvents[0].tradingValue)}
                        </div>
                        <ol className="space-y-1.5 border-l border-white/10 ml-2 pl-4">
                          {journey.map((j, k) => {
                            const jm = EVENT_META[j.eventType];
                            return (
                              <li key={`${j.eventType}-${j.occurredAt}-${k}`} className="flex items-center gap-2 text-sm">
                                <span className="num text-xs text-zinc-500 tabular-nums w-16">
                                  {clockOf(j.occurredAt)}
                                </span>
                                <span className={`text-xs font-semibold px-1.5 py-0.5 rounded ${jm.chip}`}>
                                  {jm.label}
                                </span>
                                <span className="num text-xs text-zinc-300">{detailOf(j)}</span>
                                <span className="num text-xs text-zinc-500 ml-auto">
                                  {formatPrice(j.currentPrice)}
                                </span>
                              </li>
                            );
                          })}
                        </ol>
                      </div>
                    )}
                  </motion.li>
                );
              })}
            </AnimatePresence>
          </ul>
        )}
      </section>
    </div>
  );
}
