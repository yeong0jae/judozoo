import { useEffect, useRef, useState } from "react";
import { useMe } from "../api/auth";
import LoginGate from "../components/common/LoginGate";
import { AnimatePresence, motion } from "motion/react";
import { LIVE_REFRESH_MS, useSignalEvents, useMarketSignalEvents, useLeadingStockDetail, useMarketCalendarStatus } from "../api/queries";
import type {
  SignalEventItem,
  SignalEventType,
  MarketSignalEventItem,
  MarketType,
  InvestorType,
} from "../types";
import { formatEok, formatKoreanMoney, formatPct, formatPrice } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import PageHeader from "../components/layout/PageHeader";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import GoogleLoginButton from "../components/common/GoogleLoginButton";
import StockDetailPanel from "../components/common/StockDetailPanel";
import IndexDetailPanel from "../components/common/IndexDetailPanel";
import ListDetail, { useMobileDetail } from "../components/layout/ListDetail";
import DateNavigator, { todayStr } from "../components/common/DateNavigator";
import ChangeRateSelector, { CHANGE_RATE_OPTIONS } from "../components/common/ChangeRateSelector";

const MIN_RATE_KEY = "signalLog.minRate";

const SIGNAL_START_HOUR = 8;

const rowKeyOf = (e: SignalEventItem, i: number) =>
  `${e.stockCode}-${e.eventType}-${e.occurredAt}-${i}`;

function clockOf(iso: string): string {
  return iso.slice(11, 19);
}

function clockClass(iso: string): string {
  const hm = iso.slice(11, 16);
  return hm >= "09:00" && hm <= "15:20" ? "text-zinc-100" : "text-zinc-500";
}

const EVENT_META: Record<SignalEventType, { label: string; chip: string }> = {
  VOLUME_SPIKE: { label: "스파이크", chip: "bg-rose-500/15 text-rose-300" },
  MA_REBOUND: { label: "반등", chip: "bg-zinc-700/40 text-zinc-400" },
  MA_BREAKDOWN: { label: "꺾임", chip: "bg-zinc-700/40 text-zinc-400" },
  BREAKOUT: { label: "돌파", chip: "bg-zinc-700/40 text-zinc-400" },
  BREAKOUT_IMMINENT: { label: "임박", chip: "bg-zinc-700/40 text-zinc-500" },
};

type TypeFilter = "ALL" | "MARKET" | SignalEventType;

const TYPE_TABS: { key: TypeFilter; label: string }[] = [
  { key: "ALL", label: "전체" },
  { key: "VOLUME_SPIKE", label: "스파이크" },
  { key: "MARKET", label: "지수" },
];

const MARKET_LABEL: Record<MarketType, string> = {
  KOSPI: "코스피",
  KOSDAQ: "코스닥",
};

const MARKET_CHIP: Record<MarketType, string> = {
  KOSPI: "bg-indigo-500/15 text-indigo-300",
  KOSDAQ: "bg-cyan-500/15 text-cyan-300",
};

const INVESTOR_LABEL: Record<InvestorType, string> = {
  FOREIGN: "외인",
  INSTITUTION: "기관",
  INDIVIDUAL: "개인",
};

function fmtIndex(v: number): string {
  return v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

type FeedRow =
  | { kind: "stock"; key: string; at: string; e: SignalEventItem }
  | { kind: "market"; key: string; at: string; m: MarketSignalEventItem };

function signedEok(eok: number): string {
  return `${eok > 0 ? "+" : eok < 0 ? "-" : ""}${formatEok(Math.abs(eok))}`;
}

function marketParts(m: MarketSignalEventItem) {
  const sideCls = m.side === "BUY" ? "text-red-400" : "text-blue-400";
  const netText: string | null = m.netAmountEok != null ? formatEok(Math.abs(m.netAmountEok)) : null;
  switch (m.kind) {
    case "MA_REBOUND":
      return { sideCls, accent: true, leftLabel: "반등", rightLabel: "", netText: null };
    case "MA_BREAKDOWN":
      return { sideCls, accent: true, leftLabel: "꺾임", rightLabel: "", netText: null };
    case "NET_FLOW_TURN":
      return {
        sideCls, accent: false,
        leftLabel: INVESTOR_LABEL[m.investor ?? "FOREIGN"],
        rightLabel: `${m.side === "BUY" ? "매수" : "매도"} 전환`,
        netText:
          m.extremeAmountEok != null && m.netAmountEok != null
            ? `${signedEok(m.extremeAmountEok)} → ${signedEok(m.netAmountEok)}`
            : netText,
      };
    default:
      return {
        sideCls, accent: false,
        leftLabel: INVESTOR_LABEL[m.investor ?? "FOREIGN"],
        rightLabel: `${formatEok(m.thresholdEok ?? 0)} ${m.side === "BUY" ? "순매수" : "순매도"}`,
        netText,
      };
  }
}

function renderMarketRow(
  key: string,
  m: MarketSignalEventItem,
  open: boolean,
  selected: boolean,
  onClick: () => void,
  journey: MarketSignalEventItem[],
  onShowDetail: () => void,
) {
  const { sideCls, accent, leftLabel, rightLabel } = marketParts(m);
  const leftCls = accent ? `text-[13.5px] ${sideCls}` : "text-[13.5px] text-zinc-300";
  return (
    <motion.li
      key={key}
      data-row-key={key}
      layout
      initial={{ opacity: 0, y: -6 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.2 }}
    >
      <button
        type="button"
        onClick={onClick}
        className={`flex w-full rounded-xl px-2.5 py-2.5 text-left transition-colors hover:bg-zinc-850 lg:px-3.5 ${
          selected ? "bg-selected hover:bg-selected" : ""
        }`}
      >
        <span className="flex w-full min-w-0 items-center gap-2">
          <span className={`num w-[3.75rem] shrink-0 text-xs ${clockClass(m.occurredAt)}`}>{clockOf(m.occurredAt)}</span>
          <span className={`shrink-0 rounded px-1.5 py-0.5 text-[11px] ${MARKET_CHIP[m.market]}`}>
            {MARKET_LABEL[m.market]}
          </span>
          <span className={`shrink-0 ${leftCls}`}>{leftLabel}</span>
          {rightLabel && <span className={`num min-w-0 truncate text-xs ${sideCls}`}>{rightLabel}</span>}
          <span className="ml-auto flex shrink-0 items-baseline gap-2.5 pl-2">
            <span className="num text-[13.5px] text-zinc-100">{m.indexValue != null ? fmtIndex(m.indexValue) : ""}</span>
            <span className="min-w-14 whitespace-nowrap text-right">
              {m.changeRate != null && (
                <ProfitText value={m.changeRate / 100} format={formatPct} className="num text-xs" />
              )}
            </span>
          </span>
        </span>
      </button>

      {open && (
        <div className="mx-0.5 mb-1.5 mt-0.5 rounded-xl bg-zinc-900 px-4 pb-4 pt-3">
          <div className="-mx-4 overflow-x-auto px-4">
            <table className="w-full min-w-[26rem] whitespace-nowrap text-xs num border-separate border-spacing-y-0.5">
              <thead className="text-zinc-600">
                <tr>
                  <th className="text-left font-medium pb-1">시각</th>
                  <th className="text-left font-medium pb-1">시그널</th>
                  <th className="text-right font-medium pb-1">누적</th>
                  <th className="text-right font-medium pb-1">지수</th>
                  <th className="text-right font-medium pb-1">등락</th>
                </tr>
              </thead>
              <tbody>
                {journey.map((j, k) => {
                  const p = marketParts(j);
                  return (
                    <tr key={`${j.kind}-${j.occurredAt}-${k}`}>
                      <td className="text-zinc-500 py-0.5">{clockOf(j.occurredAt)}</td>
                      <td>
                        <span className={`font-semibold ${p.sideCls}`}>{p.leftLabel}</span>
                        {p.rightLabel && <span className={p.sideCls}> {p.rightLabel}</span>}
                      </td>
                      <td className="text-right text-zinc-400">
                        {p.netText ?? "—"}
                      </td>
                      <td className="text-right text-zinc-400">
                        {j.indexValue != null ? fmtIndex(j.indexValue) : ""}
                      </td>
                      <td className="text-right">
                        {j.changeRate != null ? (
                          <ProfitText value={j.changeRate / 100} format={formatPct} className="num" />
                        ) : (
                          ""
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {onShowDetail && <ShowDetailButton label="지수 차트 보기" onClick={onShowDetail} />}
        </div>
      )}
    </motion.li>
  );
}

function detailOf(e: SignalEventItem, accumulated = true) {
  if (e.eventType === "VOLUME_SPIKE") {
    if (!e.spikeRatio) return "";
    const dirCls =
      e.spikeDirection === "BUY" ? "text-red-400" : e.spikeDirection === "SELL" ? "text-blue-400" : "text-zinc-500";
    const dirLabel =
      e.spikeDirection === "BUY" ? "매수" : e.spikeDirection === "SELL" ? "매도" : e.spikeDirection === "FLAT" ? "보합" : "";
    return (
      <>
        <span className="text-rose-300">
          {e.spikeRatio.toFixed(1)}배
          {e.minuteTradingValue != null && ` ${formatKoreanMoney(e.minuteTradingValue)}`}
        </span>
        {dirLabel && <span className={dirCls}> {dirLabel}</span>}
        {accumulated && <span className="text-zinc-500"> · 누적 {formatKoreanMoney(e.tradingValue)}</span>}
      </>
    );
  }
  if (e.eventType === "MA_REBOUND" || e.eventType === "MA_BREAKDOWN") {
    const up = e.eventType === "MA_REBOUND";
    return (
      <span className="text-zinc-500">
        5분 20이평{e.ma != null && ` ${formatPrice(e.ma)}원`}{" "}
        {up ? "상향돌파" : "하향이탈"}
      </span>
    );
  }
  if (e.gapRate == null) return e.eventType === "BREAKOUT" ? "전고 돌파" : "";
  const line = Math.round(e.currentPrice * (1 + e.gapRate / 100));
  if (e.eventType === "BREAKOUT") return `${formatPrice(line)}원 돌파`;
  return `${formatPrice(line)}원 돌파까지 ${formatPrice(line - e.currentPrice)}원 (${e.gapRate.toFixed(2)}%) 남음`;
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
    <div className="rounded-xl bg-elevated px-3 py-2">
      <div className="text-xs text-zinc-400">{label}</div>
      <div className={`num text-base font-bold ${valueClass}`}>{value}</div>
    </div>
  );
}

function StockJourney({
  stockCode,
  journey,
  onShowDetail,
}: {
  stockCode: string;
  journey: SignalEventItem[];
  onShowDetail?: () => void;
}) {
  const detailQ = useLeadingStockDetail(stockCode);
  const filters = detailQ.data?.filterResults;
  const passed = filters?.filter((f) => f.passed).length;

  const spikes = journey.filter((j) => j.eventType === "VOLUME_SPIKE");
  const buySpikes = spikes.filter((j) => j.spikeDirection === "BUY").length;
  const sellSpikes = spikes.filter((j) => j.spikeDirection === "SELL").length;
  const spikeValues = journey
    .filter((j) => j.eventType === "VOLUME_SPIKE" && j.minuteTradingValue != null)
    .map((j) => j.minuteTradingValue as number);
  const maxSpike = spikeValues.length > 0 ? Math.max(...spikeValues) : null;
  const accTradingValue = journey[0]?.tradingValue ?? 0;
  const ordered = journey;

  return (
    <div className="mx-0.5 mb-1.5 mt-0.5 rounded-xl bg-zinc-900 px-4 pb-4 pt-3">
      <div className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-2 2xl:grid-cols-4">
        <Stat label="누적 거래대금" value={formatKoreanMoney(accTradingValue)} />
        <Stat
          label="매수 · 매도 스파이크"
          value={
            <>
              <span className="text-red-400">{buySpikes}</span>
              <span className="text-zinc-600"> · </span>
              <span className="text-blue-400">{sellSpikes}</span>
            </>
          }
        />
        <Stat
          label="최대 스파이크"
          value={maxSpike != null ? formatKoreanMoney(maxSpike) : "—"}
          valueClass="text-rose-300"
        />
        <Stat
          label="주도주 조건"
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
      <div className="-mx-4 overflow-x-auto px-4">
        <table className="w-full min-w-[26rem] whitespace-nowrap text-xs num border-separate border-spacing-y-0.5">
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
                  <td className="text-right text-zinc-400">{formatPrice(j.currentPrice)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {onShowDetail && <ShowDetailButton label="종목 상세 보기" onClick={onShowDetail} />}
    </div>
  );
}

function ShowDetailButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="mt-3 flex w-full items-center justify-center gap-0.5 rounded-xl bg-zinc-850 py-2.5 text-[13px] font-medium text-zinc-100 lg:hidden"
    >
      {label}
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="text-zinc-500">
        <path d="M9 6l6 6-6 6" />
      </svg>
    </button>
  );
}

function dayLabel(iso: string): string {
  const [, m, d] = iso.split("-").map(Number);
  return `${m}월 ${d}일`;
}

function PreviewGate({ shown, total, date }: { shown: number; total: number; date: string }) {
  return (
    <li className="relative">
      <div
        aria-hidden
        className="select-none opacity-60"
        style={{
          maskImage: "linear-gradient(#000, transparent)",
          WebkitMaskImage: "linear-gradient(#000, transparent)",
        }}
      >
        {[0, 1, 2].map((i) => (
          <div key={i} className="flex items-center gap-3 px-4 py-3.5">
            <span className="h-3 w-14 rounded bg-zinc-700" />
            <span className="h-4 w-11 rounded bg-zinc-700" />
            <span className="h-3.5 w-28 rounded bg-zinc-700" />
            <span className="ml-auto h-3 w-16 rounded bg-zinc-700" />
          </div>
        ))}
      </div>
      <div className="absolute inset-0 flex flex-col items-center justify-center gap-2.5">
        <p className="text-xs text-zinc-400">
          {date === todayStr() ? "오늘" : dayLabel(date)} <span className="num">{total}</span>건 중 <span className="num">{shown}</span>건을 보고 있습니다
        </p>
        <GoogleLoginButton />
      </div>
    </li>
  );
}

function SignalLogPageInner({ authenticated }: { authenticated: boolean }) {
  const [date, setDate] = useState(todayStr());
  const krCalendar = useMarketCalendarStatus("KR").data;
  const datePicked = useRef(false);
  useEffect(() => {
    if (datePicked.current || !krCalendar?.previousOpenDay) return;
    const beforeSession = new Date().getHours() < SIGNAL_START_HOUR;
    if (krCalendar.isHoliday || beforeSession) setDate(krCalendar.previousOpenDay);
  }, [krCalendar]);
  const [minRate, setMinRate] = useState(() => {
    const raw = localStorage.getItem(MIN_RATE_KEY);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : 0;
  });
  useEffect(() => {
    localStorage.setItem(MIN_RATE_KEY, String(minRate));
  }, [minRate]);
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("ALL");
  const eventsQ = useSignalEvents(date);
  const marketQ = useMarketSignalEvents(date, authenticated);
  const data = eventsQ.data;
  const allEvents = data?.events ?? [];
  const events = authenticated
    ? allEvents.filter(
        (e) => e.priceChangeRate >= minRate && (typeFilter === "ALL" || e.eventType === typeFilter),
      )
    : allEvents;
  const allMarketEvents = marketQ.data?.events ?? [];
  const marketEvents = typeFilter === "ALL" || typeFilter === "MARKET" ? allMarketEvents : [];
  const feed: FeedRow[] = [
    ...events.map((e, i): FeedRow => ({ kind: "stock", key: rowKeyOf(e, i), at: e.occurredAt, e })),
    ...marketEvents.map(
      (m, i): FeedRow => ({
        kind: "market",
        key: `m-${m.market}-${m.investor}-${m.side}-${m.occurredAt}-${i}`,
        at: m.occurredAt,
        m,
      }),
    ),
  ].sort((a, b) => (a.at < b.at ? 1 : a.at > b.at ? -1 : 0));
  const [openKey, setOpenKey] = useState<string | null>(null);
  const mobile = useMobileDetail();

  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [selectedMarket, setSelectedMarket] = useState<MarketType | null>(null);
  const [selectedMarketAt, setSelectedMarketAt] = useState<string | null>(null);
  useEffect(() => {
    if (!authenticated) return;
    if (selectedCode === null && selectedMarket === null && events.length > 0) {
      setSelectedCode(events[0].stockCode);
    }
  }, [authenticated, events, selectedCode, selectedMarket]);

  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (!authenticated) return;
      if (ev.key !== "ArrowDown" && ev.key !== "ArrowUp") return;
      const tag = (ev.target as HTMLElement | null)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      if (events.length === 0) return;
      ev.preventDefault();
      const keys = events.map((e, i) => rowKeyOf(e, i));
      const cur = openKey ? keys.indexOf(openKey) : -1;
      const next =
        ev.key === "ArrowDown"
          ? Math.min((cur < 0 ? -1 : cur) + 1, keys.length - 1)
          : Math.max((cur < 0 ? keys.length : cur) - 1, 0);
      setOpenKey(keys[next]);
      setSelectedCode(events[next].stockCode);
      setSelectedMarket(null);
      document.querySelector(`[data-row-key="${keys[next]}"]`)?.scrollIntoView({ block: "nearest" });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [authenticated, events, openKey]);

  const list = (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-3 lg:px-3.5">
        <PageHeader
          title="주도주 시그널"
          session={false}
          count={data || marketQ.data ? feed.length : undefined}
          fetchedAt={eventsQ.dataUpdatedAt}
          refreshMs={date === todayStr() ? LIVE_REFRESH_MS : undefined}
          closedLabel={date === todayStr() ? undefined : "마감"}
          loading={eventsQ.isFetching}
        />
        <DateNavigator
          popoverAlign="left"
          date={date}
          onChange={(d) => {
            datePicked.current = true;
            setDate(d);
            setSelectedCode(null);
            setSelectedMarket(null);
          }}
        />
        {authenticated && (
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex w-fit rounded-xl bg-zinc-800 p-0.5 text-xs">
              {TYPE_TABS.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setTypeFilter(t.key)}
                  className={`rounded-lg px-[11px] py-[5.5px] transition-colors ${
                    typeFilter === t.key ? "bg-elevated font-medium text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>
            <div className="ml-auto">
              <ChangeRateSelector value={minRate} onChange={setMinRate} />
            </div>
          </div>
        )}
      </div>

      {eventsQ.isLoading || marketQ.isLoading ? (
        <div className="space-y-3 p-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : feed.length === 0 ? (
        <EmptyState message={`${date} 시그널이 없습니다`} hint="국내 종목, 지수 시그널을 조회합니다." />
      ) : (
        <ul className="flex flex-col gap-0.5">
          <AnimatePresence initial={false}>
            {feed.map((row) => {
              if (row.kind === "market") {
                const m = row.m;
                const open = openKey === row.key;
                const selected = selectedMarket === m.market && selectedCode === null;
                const journey = open ? allMarketEvents.filter((x) => x.market === m.market) : [];
                return renderMarketRow(
                  row.key,
                  m,
                  open,
                  selected,
                  () => {
                    setSelectedMarket(m.market);
                    setSelectedMarketAt(m.occurredAt);
                    setSelectedCode(null);
                    setOpenKey(open ? null : row.key);
                  },
                  journey,
                  mobile.show,
                );
              }
              const e = row.e;
              const rowKey = row.key;
              const meta = EVENT_META[e.eventType];
              const open = openKey === rowKey;
              const journey = open ? allEvents.filter((x) => x.stockCode === e.stockCode) : [];
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
                    disabled={!authenticated}
                    onClick={() => {
                      setSelectedCode(e.stockCode);
                      setSelectedMarket(null);
                      setOpenKey(open ? null : rowKey);
                    }}
                    className={`flex w-full rounded-xl px-2.5 py-2.5 text-left transition-colors lg:px-3.5 ${
                      authenticated ? "hover:bg-zinc-850" : "cursor-default"
                    } ${e.stockCode === selectedCode ? "bg-selected hover:bg-selected" : ""}`}
                  >
                    <span className="flex w-full min-w-0 items-center gap-2">
                      <span className={`num w-[3.75rem] shrink-0 text-xs ${clockClass(e.occurredAt)}`}>
                        {clockOf(e.occurredAt)}
                      </span>
                      <span className={`shrink-0 rounded px-1.5 py-0.5 text-[11px] ${meta.chip}`}>{meta.label}</span>
                      <span className="max-w-[45%] shrink-0 truncate text-[13.5px] text-zinc-100">{e.stockName}</span>
                      <span className="num min-w-0 truncate text-[13px] text-zinc-300">{detailOf(e, false)}</span>
                      <span className="ml-auto flex shrink-0 items-baseline gap-2.5 pl-2">
                        <span className="num text-[13.5px] text-zinc-100">{formatPrice(e.currentPrice)}</span>
                        <span className="min-w-14 whitespace-nowrap text-right">
                          <ProfitText value={e.priceChangeRate / 100} format={formatPct} className="num text-xs" />
                        </span>
                      </span>
                    </span>
                  </button>

                  {open && <StockJourney stockCode={e.stockCode} journey={journey} onShowDetail={mobile.show} />}
                </motion.li>
              );
            })}
          </AnimatePresence>
          {!authenticated && (data?.totalCount ?? 0) > feed.length && (
            <PreviewGate shown={feed.length} total={data?.totalCount ?? 0} date={date} />
          )}
        </ul>
      )}
    </div>
  );

  const detail = !authenticated ? (
    <LoginGate
      title="종목 상세"
      description="주도주 조건·분봉·일봉·투자자 수급을 시그널이 뜬 종목별로 봅니다. 로그인 후 확인할 수 있습니다."
    />
  ) : selectedMarket ? (
    <div className="flex flex-col gap-4">
      <BackToList onBack={mobile.hide} />
      <IndexDetailPanel
        market={selectedMarket}
        at={selectedMarketAt}
        changeRate={allMarketEvents.find((m) => m.market === selectedMarket)?.changeRate ?? null}
      />
    </div>
  ) : (
    <StockDetailPanel stockCode={selectedCode} date={date} onBack={mobile.hide} />
  );

  return <ListDetail list={list} detail={detail} detailOpen={mobile.open} />;
}

function BackToList({ onBack }: { onBack: () => void }) {
  return (
    <button
      type="button"
      onClick={onBack}
      className="-ml-1 flex w-fit items-center gap-0.5 text-sm text-zinc-400 hover:text-zinc-200 lg:hidden"
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M15 6l-6 6 6 6" />
      </svg>
      목록
    </button>
  );
}

export default function SignalLogPage() {
  const { data: me, isLoading } = useMe();
  if (isLoading) return null;
  return <SignalLogPageInner authenticated={!!me?.authenticated} />;
}
