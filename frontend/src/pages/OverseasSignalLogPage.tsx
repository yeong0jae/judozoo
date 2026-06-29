import { useEffect, useMemo, useState } from "react";
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

export default function OverseasSignalLog({ toggle }: { toggle?: React.ReactNode }) {
  const [date, setDate] = useState(todayStr());
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("ALL");
  const q = useOverseasSignalEvents(date);
  const events = useMemo(
    () => (q.data?.events ?? []).filter((e) => typeFilter === "ALL" || e.eventType === typeFilter),
    [q.data, typeFilter],
  );

  const [sel, setSel] = useState<{ exchange: string; symbol: string } | null>(null);
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
            해외 주도주 돌파·임박·스파이크 전이 · 미국장 시간대 15초 갱신
          </p>
        </div>
        <div className="flex items-center gap-3">
          {q.data && <span className="text-xs text-zinc-300 font-medium">{events.length}건</span>}
          <DateNavigator date={date} onChange={(d) => { setDate(d); setSel(null); }} />
        </div>
      </div>

      {toggle}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 items-start">
        <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
          {/* 유형 필터 — 상세 패널 토글과 동일 디자인 */}
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
              {events.map((e, i) => (
                <SignalRow
                  key={`${e.symbol}-${e.occurredAt}-${i}`}
                  e={e}
                  selected={sel?.exchange === e.exchange && sel?.symbol === e.symbol}
                  onClick={() => setSel({ exchange: e.exchange, symbol: e.symbol })}
                />
              ))}
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

function SignalRow({
  e,
  selected,
  onClick,
}: {
  e: OverseasSignalEventItem;
  selected: boolean;
  onClick: () => void;
}) {
  const isSpike = e.eventType === "VOLUME_SPIKE";
  return (
    <li
      onClick={onClick}
      className={`px-4 py-3 flex items-center gap-3 cursor-pointer hover:bg-white/[0.03] transition-colors ${
        selected ? "bg-emerald-900" : ""
      }`}
    >
      <span className="text-xs text-zinc-500 num shrink-0 w-16">{hms(e.occurredAt)}</span>
      <span className={`text-xs font-medium px-1.5 py-0.5 rounded shrink-0 ${TYPE_BADGE[e.eventType]}`}>
        {TYPE_LABEL[e.eventType]}
      </span>
      <StockAvatar name={e.symbol} code={e.symbol} size={24} />
      <div className="min-w-0 flex-1">
        <div className="font-semibold text-zinc-100 text-sm">{e.symbol}</div>
        <div className="text-xs text-zinc-500 truncate">{exchangeLabel(e.exchange)} · {e.name}</div>
      </div>
      <div className="text-right shrink-0">
        <div className="num text-sm text-zinc-100">${formatUsd(e.price)}</div>
        <ProfitText value={e.rate / 100} format={formatPct} className="num text-xs" />
      </div>
      <div className="text-right shrink-0 w-20 text-xs num">
        {isSpike
          ? e.spikeRatio != null && (
              <span className="text-rose-400">{e.spikeRatio.toFixed(1)}배</span>
            )
          : e.gapRate != null && (
              <span className={e.gapRate <= 0 ? "text-emerald-400" : "text-amber-300"}>
                {e.gapRate <= 0 ? "돌파" : `${e.gapRate.toFixed(1)}%`}
              </span>
            )}
      </div>
    </li>
  );
}
