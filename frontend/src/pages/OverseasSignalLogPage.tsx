import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useOverseasSignalEvents, useOverseasStockDetail } from "../api/queries";
import type { OverseasSignalEventItem, SignalEventType } from "../types";
import { formatPct, formatUsd } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import StockAvatar from "../components/common/StockAvatar";
import OverseasStockDetailPanel from "../components/common/OverseasStockDetailPanel";
import MarketToggle, { type StockMarket } from "../components/common/MarketToggle";
import ChangeRateSelector, { CHANGE_RATE_OPTIONS } from "../components/common/ChangeRateSelector";
import DateNavigator, { todayStr } from "../components/common/DateNavigator";

const MIN_RATE_KEY = "overseasSignalLog.minRate";

// 돌파·임박은 한 탭("돌파 / 임박")으로 묶어 함께 본다.
type TypeFilter = "ALL" | "BREAKOUT_GROUP" | SignalEventType;
const TYPE_TABS: { key: TypeFilter; label: string }[] = [
  { key: "ALL", label: "전체" },
  { key: "BREAKOUT_GROUP", label: "돌파 / 임박" },
  { key: "VOLUME_SPIKE", label: "스파이크" },
];

/** 유형 필터 매칭 — "돌파 / 임박" 그룹은 돌파·임박 둘 다 통과. */
function matchesType(eventType: SignalEventType, filter: TypeFilter): boolean {
  if (filter === "BREAKOUT_GROUP") return eventType === "BREAKOUT" || eventType === "BREAKOUT_IMMINENT";
  return eventType === filter;
}

const EVENT_META: Record<SignalEventType, { label: string; chip: string; dot: string }> = {
  BREAKOUT: { label: "돌파", chip: "bg-emerald-500/15 text-emerald-400", dot: "bg-emerald-400" },
  BREAKOUT_IMMINENT: { label: "임박", chip: "bg-amber-500/20 text-amber-300", dot: "bg-amber-300" },
  VOLUME_SPIKE: { label: "스파이크", chip: "bg-rose-500/15 text-rose-300", dot: "bg-rose-400" },
  MA20_CROSS: { label: "반등", chip: "bg-sky-500/15 text-sky-300", dot: "bg-sky-400" },
};

function clockOf(iso: string): string {
  return iso.slice(11, 19);
}
/** 미국 정규장(한국시각 22:30~익일 05:00)은 흰색, 프리·애프터는 회색. */
function clockClass(iso: string): string {
  const hm = iso.slice(11, 16);
  return hm >= "22:30" || hm <= "05:00" ? "text-zinc-100" : "text-zinc-500";
}

/** 달러 거래대금 — 정수 + 천 단위 쉼표. */
function usdAmount(v: number): string {
  return `$${Math.round(v).toLocaleString("en-US")}`;
}

function detailOf(e: OverseasSignalEventItem) {
  if (e.eventType === "VOLUME_SPIKE") {
    if (!e.spikeRatio) return "";
    const dirCls =
      e.spikeDirection === "BUY" ? "text-red-400" : e.spikeDirection === "SELL" ? "text-blue-400" : "text-zinc-500";
    const dirLabel =
      e.spikeDirection === "BUY" ? "매수" : e.spikeDirection === "SELL" ? "매도" : e.spikeDirection === "FLAT" ? "보합" : "";
    return (
      <>
        <span className="text-rose-300">
          🔥{e.spikeRatio.toFixed(1)}배
          {e.minuteTradingValue != null && ` ${usdAmount(e.minuteTradingValue)}`}
        </span>
        {dirLabel && <span className={dirCls}> {dirLabel}</span>}
        <span className="text-zinc-500"> · 누적 {usdAmount(e.tradingValue)}</span>
      </>
    );
  }
  if (e.eventType === "MA20_CROSS") {
    return (
      <span className="text-sky-300">
        5분 20이평{e.ma20 != null && ` $${formatUsd(e.ma20)}`} 상향돌파
      </span>
    );
  }
  if (e.gapRate == null) return e.eventType === "BREAKOUT" ? "전고 돌파" : "";
  const line = e.price * (1 + e.gapRate / 100);
  if (e.eventType === "BREAKOUT") return `$${formatUsd(line)} 돌파`;
  return `$${formatUsd(line)} 돌파까지 $${formatUsd(line - e.price)} (${e.gapRate.toFixed(2)}%) 남음`;
}

function Stat({
  label,
  value,
  valueClass = "text-zinc-100",
}: {
  label: string;
  value: React.ReactNode;
  valueClass?: string;
}) {
  return (
    <div className="rounded-xl bg-white/[0.03] px-3 py-2">
      <div className="text-xs text-zinc-500">{label}</div>
      <div className={`num text-base font-bold ${valueClass}`}>{value}</div>
    </div>
  );
}

/** 해외 종목 여정 — 요약 스탯 4개 + 시간순 테이블. [journey]는 최신순 → 테이블도 최신→오래된 그대로. */
function OverseasStockJourney({
  exchange,
  symbol,
  journey,
}: {
  exchange: string;
  symbol: string;
  journey: OverseasSignalEventItem[];
}) {
  const detailQ = useOverseasStockDetail(exchange, symbol);
  const filters = detailQ.data?.filterResults;
  const passed = filters?.filter((f) => f.passed).length;

  const breakouts = journey.filter((j) => j.eventType === "BREAKOUT").length;
  const spikeValues = journey
    .filter((j) => j.eventType === "VOLUME_SPIKE" && j.minuteTradingValue != null)
    .map((j) => j.minuteTradingValue as number);
  const maxSpike = spikeValues.length > 0 ? Math.max(...spikeValues) : null;
  const accTradingValue = journey[0]?.tradingValue ?? 0;
  const ordered = journey; // 최신 → 오래된

  return (
    <div className="px-4 pb-4 pt-3 bg-white/[0.02]">
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 mb-3">
        <Stat label="누적 거래대금" value={usdAmount(accTradingValue)} />
        <Stat label="오늘 돌파" value={`${breakouts}회`} valueClass="text-emerald-400" />
        <Stat
          label="최대 스파이크"
          value={maxSpike != null ? usdAmount(maxSpike) : "—"}
          valueClass="text-rose-300"
        />
        <Stat
          label="필터 충족"
          value={
            filters ? (
              <>
                <span className="text-emerald-400">{passed}</span> / {filters.length}
              </>
            ) : (
              "…"
            )
          }
        />
      </div>
      <table className="w-full text-xs num border-separate border-spacing-y-0.5">
        <thead className="text-zinc-600">
          <tr>
            <th className="text-left font-medium pb-1">시각</th>
            <th className="text-left font-medium pb-1">유형</th>
            <th className="text-left font-medium pb-1">상세</th>
            <th className="text-right font-medium pb-1">가격</th>
          </tr>
        </thead>
        <tbody>
          {ordered.map((j, k) => {
            const jm = EVENT_META[j.eventType];
            return (
              <tr key={`${j.eventType}-${j.occurredAt}-${k}`}>
                <td className="text-zinc-500 py-0.5">{clockOf(j.occurredAt)}</td>
                <td>
                  <span className={`text-[11px] font-semibold px-1.5 py-0.5 rounded ${jm.chip}`}>
                    {jm.label}
                  </span>
                </td>
                <td className="text-zinc-300">{detailOf(j)}</td>
                <td className="text-right text-zinc-400">${formatUsd(j.price)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export default function OverseasSignalLog({
  market,
  onMarket,
}: {
  market: StockMarket;
  onMarket: (m: StockMarket) => void;
}) {
  const [date, setDate] = useState(todayStr());
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("ALL");
  const [minRate, setMinRate] = useState(() => {
    const raw = localStorage.getItem(MIN_RATE_KEY);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : 0;
  });
  useEffect(() => {
    localStorage.setItem(MIN_RATE_KEY, String(minRate));
  }, [minRate]);
  const q = useOverseasSignalEvents(date);
  const allEvents = q.data?.events ?? []; // 여정용 — 필터 무관 전체
  const events = useMemo(
    () => allEvents.filter((e) => e.rate >= minRate && (typeFilter === "ALL" || matchesType(e.eventType, typeFilter))),
    [allEvents, typeFilter, minRate],
  );

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
            선택 날짜의 돌파·임박·스파이크 전이 · 행을 누르면 그 종목의 여정
          </p>
        </div>
        <div className="flex items-center gap-3">
          {q.data && <span className="text-xs text-zinc-300 font-medium">{events.length}건</span>}
          <DateNavigator date={date} onChange={(d) => { setDate(d); setSel(null); setOpenKey(null); }} />
        </div>
      </div>

      {/* 토글+필터는 목록 컬럼(50%) 폭에 맞춰 우측 정렬 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <MarketToggle
          value={market}
          onChange={onMarket}
          trailing={<ChangeRateSelector value={minRate} onChange={setMinRate} />}
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
        <section>
          <div className="py-2.5">
            <div className="flex rounded-lg bg-white/[0.04] p-0.5 text-xs w-fit">
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
                  const meta = EVENT_META[e.eventType];
                  const journey = open
                    ? allEvents.filter((x) => x.exchange === e.exchange && x.symbol === e.symbol)
                    : [];
                  return (
                    <motion.li
                      key={rowKey}
                      data-row-key={rowKey}
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
                        className={`w-full flex items-center flex-wrap gap-x-3 gap-y-1 px-4 py-3 text-left rounded-xl hover:bg-white/[0.03] transition-colors ${
                          selected ? "bg-emerald-900/30" : ""
                        }`}
                      >
                        {/* 왼쪽: 시각·유형·종목 */}
                        <div className="flex items-center gap-2 min-w-0 flex-1">
                          <span className={`num text-xs tabular-nums w-16 shrink-0 ${clockClass(e.occurredAt)}`}>
                            {clockOf(e.occurredAt)}
                          </span>
                          <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${meta.dot}`} />
                          <span className={`text-xs font-semibold px-1.5 py-0.5 rounded shrink-0 ${meta.chip}`}>
                            {meta.label}
                          </span>
                          <StockAvatar name={e.name} code={e.symbol} />
                          <span className="text-sm font-semibold text-zinc-100 truncate">{e.name}</span>
                          <span className="text-xs text-zinc-500 num shrink-0">{e.symbol}</span>
                        </div>
                        {/* 오른쪽: 디테일·현재가·등락률 */}
                        <div className="flex items-center gap-3 shrink-0 ml-auto pl-[4.5rem] md:pl-0">
                          <span className="num text-xs text-zinc-300">{detailOf(e)}</span>
                          <span className="num text-xs text-zinc-100 w-24 text-right">${formatUsd(e.price)}</span>
                          <span className="w-16 text-right">
                            <ProfitText value={e.rate / 100} format={formatPct} className="num text-xs" />
                          </span>
                        </div>
                      </button>

                      {open && journey.length > 0 && (
                        <OverseasStockJourney
                          exchange={e.exchange}
                          symbol={e.symbol}
                          journey={journey}
                        />
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
