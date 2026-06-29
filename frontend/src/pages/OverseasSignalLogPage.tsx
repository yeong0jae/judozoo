import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useOverseasSignalEvents } from "../api/queries";
import type { OverseasSignalEventItem, SignalEventType } from "../types";
import { formatPct, formatUsd } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import StockAvatar from "../components/common/StockAvatar";
import OverseasStockDetailPanel, {
  exchangeLabel,
} from "../components/common/OverseasStockDetailPanel";
import DateNavigator, { todayStr } from "../components/common/DateNavigator";

const TYPE_LABEL: Record<SignalEventType, string> = {
  BREAKOUT: "돌파",
  BREAKOUT_IMMINENT: "임박",
  VOLUME_SPIKE: "스파이크",
};
const TYPE_BADGE: Record<SignalEventType, string> = {
  BREAKOUT: "bg-emerald-500/15 text-emerald-400",
  BREAKOUT_IMMINENT: "bg-amber-500/20 text-amber-300",
  VOLUME_SPIKE: "bg-rose-500/15 text-rose-400",
};
const SPIKE_DIR: Record<string, string> = { BUY: "매수", SELL: "매도", FLAT: "보합" };

type TypeFilter = "ALL" | SignalEventType;
const TYPE_TABS: { key: TypeFilter; label: string }[] = [
  { key: "ALL", label: "전체" },
  { key: "BREAKOUT", label: "돌파" },
  { key: "BREAKOUT_IMMINENT", label: "임박" },
  { key: "VOLUME_SPIKE", label: "스파이크" },
];

/** 시각 ISO → HH:mm:ss (KST 벽시계 그대로). */
function hms(iso: string): string {
  return iso.slice(11, 19);
}

/** 시그널 디테일 한 줄 — 스파이크는 배율·방향, 돌파 계열은 돌파/잔여%. */
function detailOf(e: OverseasSignalEventItem): string {
  if (e.eventType === "VOLUME_SPIKE") {
    const dir = e.spikeDirection ? ` ${SPIKE_DIR[e.spikeDirection] ?? ""}` : "";
    return e.spikeRatio != null ? `${e.spikeRatio.toFixed(1)}배${dir}` : "";
  }
  if (e.gapRate == null) return "";
  return e.gapRate <= 0 ? "돌파" : `${e.gapRate.toFixed(1)}% 남음`;
}

export default function OverseasSignalLog({ toggle }: { toggle?: React.ReactNode }) {
  const [date, setDate] = useState(todayStr());
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("ALL");
  const q = useOverseasSignalEvents(date);
  const allEvents = q.data?.events ?? []; // 여정용 — 필터 무관 전체
  const events = useMemo(
    () => allEvents.filter((e) => typeFilter === "ALL" || e.eventType === typeFilter),
    [allEvents, typeFilter],
  );

  // 우측 패널 선택 종목 + 펼친 행
  const [sel, setSel] = useState<{ exchange: string; symbol: string } | null>(null);
  const [openKey, setOpenKey] = useState<string | null>(null);
  useEffect(() => {
    if (events.length === 0) return;
    if (sel === null || !events.some((e) => e.exchange === sel.exchange && e.symbol === sel.symbol)) {
      setSel({ exchange: events[0].exchange, symbol: events[0].symbol });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events]);

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
        <div>
          <h2 className="text-xl font-bold flex items-center gap-2">
            실시간 로그
            <span
              className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
                q.isFetching ? "animate-ping" : "animate-pulse"
              }`}
            />
          </h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            해외 주도주 돌파·임박·스파이크 전이 · 행을 누르면 그 종목의 여정 · 미국장 15초 갱신
          </p>
        </div>
        <div className="flex items-center gap-3">
          {q.data && <span className="text-xs text-zinc-300 font-medium">{events.length}건</span>}
          <DateNavigator date={date} onChange={(d) => { setDate(d); setSel(null); setOpenKey(null); }} />
        </div>
      </div>

      {toggle}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 items-start">
        <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
          <div className="flex px-4 py-2.5 border-b border-white/[0.04]">
            <div className="flex rounded-lg bg-white/[0.04] p-0.5 text-xs">
              {TYPE_TABS.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setTypeFilter(t.key)}
                  className={`px-2.5 py-1 rounded-md transition-colors ${
                    typeFilter === t.key ? "bg-white/[0.1] text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </div>
          {q.isLoading ? (
            <div className="p-6 space-y-3">
              {Array.from({ length: 6 }).map((_, i) => (
                <Skeleton key={i} className="h-10 w-full" />
              ))}
            </div>
          ) : events.length === 0 ? (
            <EmptyState message={`${date} 시그널이 없습니다`} />
          ) : (
            <ul className="divide-y divide-white/[0.04]">
              <AnimatePresence initial={false}>
                {events.map((e, i) => {
                  const rowKey = `${e.exchange}:${e.symbol}-${e.occurredAt}-${i}`;
                  const open = openKey === rowKey;
                  const selected = sel?.exchange === e.exchange && sel?.symbol === e.symbol;
                  const journey = open
                    ? allEvents.filter((x) => x.exchange === e.exchange && x.symbol === e.symbol)
                    : [];
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
                        onClick={() => {
                          setSel({ exchange: e.exchange, symbol: e.symbol });
                          setOpenKey(open ? null : rowKey);
                        }}
                        className={`w-full flex items-center gap-3 px-4 py-3 text-left hover:bg-white/[0.03] transition-colors ${
                          selected ? "bg-emerald-900/30" : ""
                        }`}
                      >
                        <span className="num text-xs text-zinc-500 w-16 shrink-0">{hms(e.occurredAt)}</span>
                        <span className={`text-xs font-semibold px-1.5 py-0.5 rounded shrink-0 ${TYPE_BADGE[e.eventType]}`}>
                          {TYPE_LABEL[e.eventType]}
                        </span>
                        <StockAvatar name={e.symbol} code={e.symbol} size={24} />
                        <div className="min-w-0 flex-1">
                          <div className="font-semibold text-zinc-100 text-sm">{e.symbol}</div>
                          <div className="text-xs text-zinc-500 truncate">
                            {exchangeLabel(e.exchange)} · {e.name}
                          </div>
                        </div>
                        <span className="num text-xs text-zinc-300 shrink-0">{detailOf(e)}</span>
                        <span className="num text-xs text-zinc-100 w-20 text-right shrink-0">${formatUsd(e.price)}</span>
                        <span className="w-16 text-right shrink-0">
                          <ProfitText value={e.rate / 100} format={formatPct} className="num text-xs" />
                        </span>
                      </button>

                      {open && journey.length > 0 && (
                        <div className="px-4 pb-3 pt-1 bg-white/[0.02]">
                          <div className="text-xs text-zinc-500 mb-2">{e.name} 여정</div>
                          <ol className="space-y-1.5 border-l border-white/10 ml-2 pl-4">
                            {journey.map((j, k) => (
                              <li key={`${j.eventType}-${j.occurredAt}-${k}`} className="flex items-center gap-2 text-sm">
                                <span className="num text-xs text-zinc-500 w-16">{hms(j.occurredAt)}</span>
                                <span className={`text-xs font-semibold px-1.5 py-0.5 rounded ${TYPE_BADGE[j.eventType]}`}>
                                  {TYPE_LABEL[j.eventType]}
                                </span>
                                <span className="num text-xs text-zinc-300">{detailOf(j)}</span>
                                <span className="num text-xs text-zinc-500 ml-auto">${formatUsd(j.price)}</span>
                              </li>
                            ))}
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

        <div className="lg:sticky lg:top-20">
          <OverseasStockDetailPanel exchange={sel?.exchange ?? null} symbol={sel?.symbol ?? null} />
        </div>
      </div>
    </div>
  );
}
