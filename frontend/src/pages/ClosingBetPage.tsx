import { useMemo, useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  useFuturesCandles,
  useFuturesQuote,
  useKospiIndex,
  useMarketCandles,
  useMarketInvestorDaily,
  useMarketInvestorSessions,
} from "../api/queries";
import CandleChart from "../components/common/CandleChart";
import { todayStr } from "../components/common/DateNavigator";
import EmptyState from "../components/common/EmptyState";
import Skeleton from "../components/common/Skeleton";
import StockAvatar from "../components/common/StockAvatar";
import { colorByPnL, formatEok, formatPct, formatPrice } from "../lib/format";
import type { MarketInvestorDay, MarketType } from "../types";
import {
  changeAmount,
  highDistance,
  INDICES,
  investorDays,
  MARKET_NEWS,
  mockSeries,
  type CbIndex,
  type CbTheme,
  type NewsItem,
  nxtFlow,
  orgBreakdown,
  relIndicator,
  sessions,
  stockByName,
  stockNews,
  themeByName,
  THEMES,
  themeNews,
  tradingValues,
} from "../components/closingbet/mockData";
import { marketDailySeries, marketMinuteSeries } from "../components/common/tossCandles";

// 선택 대상 — 종목 / 테마 / 지수
type Selection =
  | { kind: "stock"; name: string }
  | { kind: "theme"; name: string }
  | { kind: "index"; id: string };

/** 실데이터(토스 Market Indicators) 연동이 끝난 지수 — 그 외는 아직 목데이터. */
const LIVE_MARKET: Record<string, MarketType> = { kospi: "KOSPI" };

/**
 * 시황분석 — 장 막판 매수 판단용 지표 집약 대시보드. 코스피는 실데이터(토스), 나머지는 목 데이터.
 * 좌: 테마 관심목록 / 중앙: 선택 대상(종목·테마·지수) 상세 / 우: 뉴스.
 */
export default function ClosingBetPage() {
  const [sel, setSel] = useState<Selection>({ kind: "index", id: "kospi" });
  const [query, setQuery] = useState("");

  return (
    <div className="flex flex-col gap-4">
      <MarketStrip sel={sel} onSelect={(id) => setSel({ kind: "index", id })} />

      <div className="grid grid-cols-1 lg:grid-cols-[20rem_minmax(0,1fr)_18rem] gap-4 lg:h-[calc(100dvh-15rem)] lg:min-h-[40rem]">
        <ThemeWatchlist
          sel={sel}
          query={query}
          setQuery={setQuery}
          onTheme={(name) => setSel({ kind: "theme", name })}
          onStock={(name) => setSel({ kind: "stock", name })}
        />
        <div className="min-h-0 lg:overflow-y-auto pr-1">
          <AnimatePresence mode="wait">
            <motion.div
              key={`${sel.kind}-${sel.kind === "index" ? sel.id : sel.name}`}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
            >
              <SubjectDetail sel={sel} onStock={(name) => setSel({ kind: "stock", name })} />
            </motion.div>
          </AnimatePresence>
        </div>
        <NewsPanel sel={sel} />
      </div>
    </div>
  );
}

// ============================================================
// 상단 시장 스트립 — 지수/선물 카드 + 분위기 메모
// ============================================================
const fmt2 = (v: number) =>
  v.toLocaleString("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const chgText = (value: number, pct: number) => {
  const chg = value - value / (1 + pct / 100);
  const sign = chg > 0 ? "+" : chg < 0 ? "−" : "";
  return `${sign}${Math.abs(chg).toLocaleString("ko-KR", { maximumFractionDigits: 2 })} (${formatPct(pct / 100)})`;
};

/** 상단 시장 스트립 — 배경 없이 페이지에 얹히고, 동일폭 5칸을 얇은 구분선으로만 분리. */
function MarketStrip({ sel, onSelect }: { sel: Selection; onSelect: (id: string) => void }) {
  const kospi = useKospiIndex();
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 divide-x divide-y lg:divide-y-0 divide-white/[0.06]">
      {INDICES.map((ix) => {
        const value = ix.id === "kospi" ? kospi.data?.currentValue ?? ix.value : ix.value;
        const pct = ix.id === "kospi" ? kospi.data?.changeRate ?? ix.pct : ix.pct;
        return (
          <IndexCell
            key={ix.id}
            ix={ix}
            value={value}
            pct={pct}
            active={sel.kind === "index" && sel.id === ix.id}
            onSelect={() => onSelect(ix.id)}
          />
        );
      })}
    </div>
  );
}

function IndexCell({
  ix,
  value,
  pct,
  active,
  onSelect,
}: {
  ix: CbIndex;
  value: number;
  pct: number;
  active: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`flex flex-col px-3.5 py-3 text-left transition-colors ${
        active ? "bg-blue-500/[0.08]" : "hover:bg-white/[0.03]"
      }`}
    >
      <span className="text-[14px] font-medium text-zinc-300">{ix.name}</span>
      <div className="flex items-baseline gap-1.5 flex-wrap mt-0.5">
        <span className="num text-[17px] font-bold text-zinc-100">{fmt2(value)}</span>
        <span className={`num text-[14px] font-medium ${colorByPnL(pct)}`}>{chgText(value, pct)}</span>
      </div>
    </button>
  );
}

// ============================================================
// 좌: 테마 관심목록
// ============================================================
function ThemeWatchlist({
  sel,
  query,
  setQuery,
  onTheme,
  onStock,
}: {
  sel: Selection;
  query: string;
  setQuery: (v: string) => void;
  onTheme: (name: string) => void;
  onStock: (name: string) => void;
}) {
  // 기본으로 앞 3개만 펼침
  const [collapsed, setCollapsed] = useState<Set<string>>(
    () => new Set(THEMES.slice(3).map((t) => t.name)),
  );
  const toggle = (name: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      next.has(name) ? next.delete(name) : next.add(name);
      return next;
    });

  const q = query.trim();

  return (
    <div className="min-h-0 flex flex-col overflow-hidden">
      <div className="px-4 pt-4 pb-2">
        <span className="text-base font-bold text-zinc-100">테마</span>
      </div>
      <div className="px-4 pb-2">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="종목 검색"
          className="w-full rounded-xl bg-zinc-950/70 border border-white/[0.06] px-3 py-2 text-sm text-zinc-200 placeholder:text-zinc-600 outline-none transition-colors focus:border-white/20 focus:bg-zinc-950"
        />
      </div>
      <div className="flex-1 overflow-y-auto">
        {THEMES.map((t) => {
          const rows = q ? t.stocks.filter((s) => s.name.includes(q)) : t.stocks;
          if (q && rows.length === 0) return null;
          const isOpen = q ? true : !collapsed.has(t.name);
          const themeSel = sel.kind === "theme" && sel.name === t.name;
          return (
            <div key={t.name} className="border-t border-white/[0.04]">
              <div
                className={`flex items-center justify-between pl-3.5 pr-2 py-2 rounded-xl transition-colors ${
                  themeSel ? "bg-blue-500/[0.08]" : "hover:bg-white/[0.03]"
                }`}
              >
                <button type="button" onClick={() => onTheme(t.name)} className="flex-1 flex items-baseline gap-2 text-left">
                  <span className="text-sm font-bold text-zinc-100">{t.name}</span>
                  <span className="text-[11px] text-zinc-500">{t.stocks.length}</span>
                </button>
                <button
                  type="button"
                  onClick={() => toggle(t.name)}
                  aria-label={isOpen ? "접기" : "펼치기"}
                  className="p-1 rounded-md text-zinc-500 hover:bg-white/[0.06]"
                >
                  <svg
                    width="14"
                    height="14"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.2"
                    className={`transition-transform ${isOpen ? "" : "-rotate-90"}`}
                  >
                    <path d="m6 9 6 6 6-6" />
                  </svg>
                </button>
              </div>
              {isOpen && (
                <>
                  {rows.map((s) => {
                    const active = sel.kind === "stock" && sel.name === s.name;
                    return (
                      <button
                        key={s.code}
                        type="button"
                        onClick={() => onStock(s.name)}
                        className={`w-full flex items-center gap-2.5 pl-3.5 pr-4 py-2 rounded-xl text-left transition-colors ${
                          active ? "bg-blue-500/[0.08]" : "hover:bg-white/[0.03]"
                        }`}
                      >
                        <StockAvatar name={s.name} code={s.code} size={30} />
                        <span className="flex-1 min-w-0 text-[13px] text-zinc-200 leading-tight line-clamp-2">{s.name}</span>
                        <span className="text-right shrink-0">
                          <span className="block num text-[13px] font-semibold text-zinc-100">{formatPrice(s.price)}</span>
                          <span className={`block num text-[11px] ${colorByPnL(s.pct)}`}>{formatPct(s.pct / 100)}</span>
                        </span>
                      </button>
                    );
                  })}
                </>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ============================================================
// 중앙: 선택 대상 상세
// ============================================================
function SubjectDetail({ sel, onStock }: { sel: Selection; onStock: (name: string) => void }) {
  if (sel.kind === "theme") {
    const t = themeByName[sel.name];
    return t ? <ThemeDetail theme={t} onStock={onStock} /> : null;
  }
  if (sel.kind === "index") {
    const ix = INDICES.find((i) => i.id === sel.id);
    if (!ix) return null;
    if (ix.id === "kospiF") return <FuturesIndexDetail index={ix} />;
    const liveMarket = LIVE_MARKET[ix.id];
    return liveMarket ? <LiveIndexDetail market={liveMarket} fallback={ix} /> : <IndexDetail index={ix} />;
  }
  const entry = stockByName[sel.name];
  return entry ? <StockDetail name={sel.name} theme={entry.theme} /> : null;
}

const cardCls = "bg-zinc-900 rounded-2xl p-4";
const titleCls = "text-sm font-semibold text-zinc-400";

function Amount({ eok }: { eok: number }) {
  // 한국 관행 — 매수(양) 빨강 / 매도(음) 파랑
  const tone = eok > 0 ? "text-red-400" : eok < 0 ? "text-blue-400" : "text-zinc-500";
  const sign = eok > 0 ? "+" : eok < 0 ? "−" : "";
  return (
    <span className={`num ${tone}`}>
      {sign}
      {formatEok(Math.abs(eok))}
    </span>
  );
}

type DetailTab = "detail" | "minute" | "daily";
const DETAIL_TABS: { key: DetailTab; label: string }[] = [
  { key: "detail", label: "상세" },
  { key: "minute", label: "1분봉" },
  { key: "daily", label: "일봉" },
];

/** 주도주 후보 조회 상세 패널 톤 헤더 — 아바타·이름·코드·분류 / 가격·등락 / 상세·1분봉·일봉 탭. */
function DetailHeader({
  avatar,
  name,
  code,
  category,
  price,
  pct,
  extra,
  tab,
  setTab,
  priceInline = false,
}: {
  avatar?: ReactNode;
  name: string;
  code?: string;
  category: string;
  price: number;
  pct: number;
  extra?: ReactNode;
  tab?: DetailTab;
  setTab?: (t: DetailTab) => void;
  priceInline?: boolean;
}) {
  const chg = changeAmount(price, pct);
  const priceGroup = (
    <>
      <span className="num text-xl font-bold text-zinc-100">{formatPrice(price)}</span>
      <span className={`num text-sm font-semibold ${colorByPnL(pct)}`}>
        {chg > 0 ? "+" : chg < 0 ? "−" : ""}
        {Math.abs(chg).toLocaleString("ko-KR")} ({formatPct(pct / 100)})
      </span>
      {extra}
    </>
  );
  return (
    <header className="pb-4 border-b border-zinc-800">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          {priceInline ? (
            <div className="flex items-baseline flex-wrap gap-x-3 gap-y-1">
              <span className="text-lg font-bold tracking-tight text-zinc-100">{name}</span>
              <span className="inline-flex items-baseline gap-2">{priceGroup}</span>
              <span className="self-center text-xs text-zinc-400 bg-white/[0.04] rounded px-1.5 py-0.5">{category}</span>
            </div>
          ) : (
            <>
              <div className="flex items-center gap-3">
                {avatar}
                <div className="flex items-center flex-wrap gap-x-2 gap-y-1">
                  <span className="text-lg font-bold tracking-tight text-zinc-100">{name}</span>
                  {code && <span className="num text-xs text-zinc-500">{code}</span>}
                  <span className="text-xs text-zinc-400 bg-white/[0.04] rounded px-1.5 py-0.5">{category}</span>
                </div>
              </div>
              <div className="mt-1 flex items-baseline gap-2 flex-wrap">{priceGroup}</div>
            </>
          )}
        </div>
        {tab && setTab && (
          <div className="flex rounded-xl bg-white/[0.04] p-0.5 text-xs shrink-0">
            {DETAIL_TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => setTab(t.key)}
                className={`px-3 py-1.5 rounded-lg transition-colors ${
                  tab === t.key ? "bg-white/[0.1] text-zinc-100 font-medium" : "text-zinc-500 hover:text-zinc-300"
                }`}
              >
                {t.label}
              </button>
            ))}
          </div>
        )}
      </div>
    </header>
  );
}

/** 목 차트(1분봉/일봉) — 카드에 차트만. */
function MockChart({ chartKey, minute }: { chartKey: string; minute: boolean }) {
  const series = useMemo(() => mockSeries(chartKey, minute), [chartKey, minute]);
  return (
    <div className="px-1">
      <CandleChart key={`${chartKey}-${minute}`} series={series} timeVisible={minute} className="w-full h-[21.25rem]" />
    </div>
  );
}

function InvestorTable({ dataKey }: { dataKey: string }) {
  const days = investorDays(dataKey);
  const org = orgBreakdown(dataKey);
  return (
    <div className={cardCls}>
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>최근 10일 수급</span>
        <span className="text-xs text-zinc-600">단위 억원</span>
      </div>
      <table className="w-full text-xs">
        <thead>
          <tr className="text-zinc-500">
            <th className="text-left font-medium pb-1.5">일자</th>
            <th className="text-right font-medium pb-1.5">개인</th>
            <th className="text-right font-medium pb-1.5">외국인</th>
            <th className="text-right font-medium pb-1.5">기관</th>
          </tr>
        </thead>
        <tbody>
          {days.map((d) => (
            <tr key={d.date} className="border-t border-zinc-800/60">
              <td className="text-left text-zinc-400 num py-1">{d.date}</td>
              <td className="text-right py-1">
                <Amount eok={d.indiv} />
              </td>
              <td className="text-right py-1">
                <Amount eok={d.foreign} />
              </td>
              <td className="text-right py-1">
                <Amount eok={d.inst} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="mt-3 text-[11px] text-zinc-500">기관 세부 순매수 · 오늘</div>
      <div className="mt-1.5 flex flex-wrap gap-1.5">
        {org.map((o) => (
          <span key={o.label} className="text-[11px] rounded-md bg-zinc-800/60 px-2 py-1 text-zinc-400">
            {o.label} <Amount eok={o.value} />
          </span>
        ))}
      </div>
    </div>
  );
}

function TradingValueCard({ dataKey }: { dataKey: string }) {
  const vals = tradingValues(dataKey);
  const max = Math.max(...vals.map((v) => v.eok));
  return (
    <div className={cardCls}>
      <span className={titleCls}>일별 거래대금</span>
      <div className="mt-3 flex items-end gap-1.5 h-28">
        {vals.map((v) => {
          const hot = v.eok > max * 0.8;
          return (
            <div key={v.date} className="flex-1 flex flex-col items-center gap-1.5">
              <span className="num text-[9px] text-zinc-500">{(v.eok / 1000).toFixed(1)}천억</span>
              <div
                className={`w-3/5 rounded-t ${hot ? "bg-red-400" : "bg-zinc-700"}`}
                style={{ height: `${Math.round((v.eok / max) * 70)}px` }}
              />
              <span className="num text-[9px] text-zinc-600">{v.date}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function SessionsCard({ dataKey }: { dataKey: string }) {
  const list = sessions(dataKey);
  return (
    <div className={cardCls}>
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>정규장 시간대별 수급</span>
        <span className="text-xs text-zinc-600">오늘 · 억원</span>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
        {list.map((s) => (
          <div key={s.name} className="rounded-xl bg-white/[0.02] p-3">
            <div className="flex items-start justify-between mb-2">
              <div>
                <div className="text-xs text-zinc-300">{s.name}</div>
                <div className="text-[10px] text-zinc-600 num">{s.time}</div>
              </div>
              {s.tag && <span className="text-[9px] text-blue-500 font-semibold">{s.tag}</span>}
            </div>
            {[
              ["외인", s.foreign],
              ["기관", s.inst],
              ["개인", s.indiv],
            ].map(([label, v]) => (
              <div key={label as string} className="flex justify-between text-xs py-0.5">
                <span className="text-zinc-500">{label}</span>
                <Amount eok={v as number} />
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

function NxtCard({ dataKey }: { dataKey: string }) {
  const series = useMemo(() => mockSeries(dataKey + "nxt", true), [dataKey]);
  const flow = nxtFlow(dataKey);
  return (
    <div className={cardCls}>
      <div className="flex items-center justify-between mb-3">
        <span className={titleCls}>
          NXT <span className="text-zinc-600 font-normal">· 넥스트레이드</span>
        </span>
        <span className="text-xs px-2 py-0.5 rounded-md bg-red-400/10 text-red-400">추세 강세</span>
      </div>
      <CandleChart key={`${dataKey}-nxt`} series={series} timeVisible className="w-full h-56" />
      <div className="mt-3 flex gap-5 text-xs">
        <span className="text-zinc-500">
          외인 <Amount eok={flow.foreign} />
        </span>
        <span className="text-zinc-500">
          기관 <Amount eok={flow.inst} />
        </span>
        <span className="text-zinc-500">
          개인 <Amount eok={flow.indiv} />
        </span>
      </div>
    </div>
  );
}

function StockDetail({ name, theme }: { name: string; theme: string }) {
  const { stock } = stockByName[name];
  const hi = highDistance(name);
  const [tab, setTab] = useState<DetailTab>("detail");
  return (
    <div className="flex flex-col gap-4">
      <DetailHeader
        avatar={<StockAvatar name={name} code={stock.code} size={40} />}
        name={name}
        code={stock.code}
        category={theme}
        price={stock.price}
        pct={stock.pct}
        extra={
          <span className="inline-flex items-center gap-1.5 text-xs bg-white/[0.04] rounded-lg px-2.5 py-1">
            <span className="text-zinc-500">신고가까지</span>
            <span className="num text-blue-400 font-medium">{hi.toFixed(1)}%</span>
            <span className="text-zinc-600">· 52주</span>
          </span>
        }
        tab={tab}
        setTab={setTab}
      />
      {tab === "detail" ? (
        <>
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
            <InvestorTable dataKey={name} />
            <TradingValueCard dataKey={name} />
          </div>
          <SessionsCard dataKey={name} />
          <NxtCard dataKey={name} />
        </>
      ) : (
        <MockChart chartKey={name} minute={tab === "minute"} />
      )}
    </div>
  );
}

type ChartInterval = "1m" | "1d";
const INTERVAL_TABS: { key: ChartInterval; label: string }[] = [
  { key: "1m", label: "1분봉" },
  { key: "1d", label: "일봉" },
];

/** 차트 위 1분봉/일봉 토글. */
function IntervalToggle({ value, onChange }: { value: ChartInterval; onChange: (v: ChartInterval) => void }) {
  return (
    <div className="flex rounded-xl bg-white/[0.04] p-0.5 text-xs shrink-0">
      {INTERVAL_TABS.map((t) => (
        <button
          key={t.key}
          type="button"
          onClick={() => onChange(t.key)}
          className={`px-3 py-1.5 rounded-lg transition-colors ${
            value === t.key ? "bg-white/[0.1] text-zinc-100 font-medium" : "text-zinc-500 hover:text-zinc-300"
          }`}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

/** 목데이터 지수 상세(비-코스피). */
function IndexDetail({ index }: { index: CbIndex }) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  return (
    <div className="flex flex-col gap-4">
      <DetailHeader name={index.name} category="지수" price={index.value} pct={index.pct} priceInline />
      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>지수 차트</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <MockChart chartKey={index.id} minute={chartInterval === "1m"} />
      </div>
      <SessionsCard dataKey={index.id} />
      <InvestorTable dataKey={index.id} />
    </div>
  );
}

/** 실데이터 지수 상세(코스피) — 지수값·1분봉·일봉은 토스, 거래원은 키움. */
function LiveIndexDetail({ market, fallback }: { market: MarketType; fallback: CbIndex }) {
  const date = todayStr();
  const priceQ = useKospiIndex();
  const value = priceQ.data?.currentValue ?? fallback.value;
  const pct = priceQ.data?.changeRate ?? fallback.pct;
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");

  return (
    <div className="flex flex-col gap-4">
      <DetailHeader name={fallback.name} category="지수" price={value} pct={pct} priceInline />
      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>지수 차트</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <LiveChart market={market} interval={chartInterval} />
      </div>
      <RealSessionsCard market={market} date={date} />
      <RealInvestorTable market={market} />
    </div>
  );
}

/**
 * 코스피 선물 상세 — 종가베팅용. 헤더(선물가·베이시스·만기) + 베이시스 패널 + 차트.
 * ⚠️ 목데이터(화면 구성 우선). KIS 선물시세(FHMIF10000000) 연동 시 값 교체 예정.
 */
function FuturesIndexDetail({ index }: { index: CbIndex }) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1d");
  const { data } = useFuturesQuote();

  // 실데이터 우선, 없으면 목값(fallback)
  const futValue = data?.futuresPrice ?? index.value;
  const futPct = data?.changeRate ?? index.pct;
  const basis = data?.basis ?? 0.45; // 시장 베이시스 = 선물 − 현물(KOSPI200)
  const spot = data?.spot ?? futValue - basis; // 현물 KOSPI200
  const dprt = data?.dprt ?? 0.11; // 괴리율(%)
  const spotPct = data?.spotChangeRate ?? 0.82; // 현물 등락률
  const oi = data?.openInterest ?? 285432; // 미결제약정(계약)
  const oiChg = data?.openInterestChange ?? 3210; // 전일 대비 증감
  const expiryDate = data?.expiryDate ?? "2026-09-10"; // 만기일
  const strengthDiff = futPct - spotPct; // 선물 − 현물 상대강도(%p)
  const contango = basis >= 0;
  const tone = contango ? "text-red-400" : "text-blue-400";
  const badge = contango ? "bg-red-500/10 text-red-400" : "bg-blue-500/10 text-blue-400";
  const sTone = strengthDiff >= 0 ? "text-red-400" : "text-blue-400";
  const sBadge = strengthDiff >= 0 ? "bg-red-500/10 text-red-400" : "bg-blue-500/10 text-blue-400";

  return (
    <div className="flex flex-col gap-4">
      <DetailHeader name={index.name} category="지수선물" price={futValue} pct={futPct} priceInline />

      <div className="px-1">
        <div className="flex items-baseline justify-between mb-3">
          <span className={titleCls}>베이시스</span>
          <span className="text-xs text-zinc-600">선물 − 현물(KOSPI200)</span>
        </div>
        <div className="flex items-end gap-3 flex-wrap">
          <span className={`num text-3xl font-bold ${tone}`}>
            {basis >= 0 ? "+" : "−"}
            {Math.abs(basis).toFixed(2)}
          </span>
          <span className={`mb-1 text-xs font-medium rounded-md px-2 py-1 ${badge}`}>
            {contango ? "콘탱고 · 선물 우위" : "백워데이션 · 현물 우위"}
          </span>
        </div>
        <div className="mt-3 grid grid-cols-3 gap-2">
          {[
            { label: "선물", value: fmt2(futValue) },
            { label: "현물 K200", value: fmt2(spot) },
            { label: "괴리율", value: `${dprt >= 0 ? "+" : "−"}${Math.abs(dprt).toFixed(2)}%` },
          ].map((c) => (
            <div key={c.label} className="rounded-xl bg-white/[0.02] px-3 py-2.5">
              <div className="text-xs text-zinc-500">{c.label}</div>
              <div className="num text-lg font-semibold text-zinc-100 mt-0.5">{c.value}</div>
            </div>
          ))}
        </div>
      </div>

      <div className="px-1">
        <div className="flex items-baseline justify-between mb-3">
          <span className={titleCls}>선물 강도 · 미결제</span>
          <span className="text-xs text-zinc-600">전일 대비</span>
        </div>
        <div className="flex items-center gap-2 flex-wrap text-sm">
          <span className="text-xs text-zinc-500">선물</span>
          <span className={`num font-semibold ${colorByPnL(futPct)}`}>{formatPct(futPct / 100)}</span>
          <span className="text-xs text-zinc-600">vs 현물</span>
          <span className={`num font-semibold ${colorByPnL(spotPct)}`}>{formatPct(spotPct / 100)}</span>
          <span className="text-zinc-600">→</span>
          <span className={`num font-semibold ${sTone}`}>
            {strengthDiff >= 0 ? "+" : "−"}
            {Math.abs(strengthDiff).toFixed(2)}%p
          </span>
          <span className={`text-xs font-medium rounded-md px-2 py-0.5 ${sBadge}`}>
            {strengthDiff >= 0 ? "선물 우위" : "현물 우위"}
          </span>
        </div>
        <div className="mt-2.5 flex items-baseline gap-2 text-sm">
          <span className="text-xs text-zinc-500">미결제약정</span>
          <span className="num font-semibold text-zinc-100">{oi.toLocaleString("ko-KR")}</span>
          <span className="text-xs text-zinc-600">계약</span>
          <span className="num text-xs text-zinc-400">
            {oiChg >= 0 ? "+" : "−"}
            {Math.abs(oiChg).toLocaleString("ko-KR")} 전일
          </span>
        </div>
        <div className="mt-1.5 flex items-baseline gap-2 text-sm">
          <span className="text-xs text-zinc-500">만기일</span>
          <span className="num font-semibold text-zinc-100">{expiryDate}</span>
        </div>
      </div>

      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>선물 차트</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <FuturesChart interval={chartInterval} />
      </div>
    </div>
  );
}

/** 코스피 선물 1분봉/일봉 — KIS 근월물 캔들(OHLCV). */
function FuturesChart({ interval }: { interval: ChartInterval }) {
  const { data, isLoading } = useFuturesCandles(interval);
  const items = data ?? [];
  const series = useMemo(
    () => (interval === "1d" ? marketDailySeries(items) : marketMinuteSeries(items)),
    [items, interval],
  );
  return isLoading ? (
    <Skeleton className="h-[21.25rem] w-full" />
  ) : items.length === 0 ? (
    <div className="h-[21.25rem] flex items-center justify-center">
      <EmptyState message={interval === "1m" ? "장중에 1분봉이 표시됩니다" : "일봉 데이터가 없습니다"} />
    </div>
  ) : (
    <CandleChart
      key={`futures-${interval}`}
      series={series}
      timeVisible={interval === "1m"}
      priceDecimals={2}
      className="w-full h-[21.25rem]"
    />
  );
}

/** 지수 1분봉/일봉 — 토스 캔들(OHLCV). */
function LiveChart({ market, interval }: { market: MarketType; interval: "1m" | "1d" }) {
  const { data, isLoading } = useMarketCandles(market, interval);
  const items = data ?? [];
  const series = useMemo(
    () => (interval === "1d" ? marketDailySeries(items) : marketMinuteSeries(items)),
    [items, interval],
  );
  return (
    <div className="px-1">
      {isLoading ? (
        <Skeleton className="h-[21.25rem] w-full" />
      ) : items.length === 0 ? (
        <div className="h-[21.25rem] flex items-center justify-center">
          <EmptyState message={interval === "1m" ? "장중에 1분봉이 표시됩니다" : "일봉 데이터가 없습니다"} />
        </div>
      ) : (
        <CandleChart
          key={`${market}-${interval}`}
          series={series}
          timeVisible={interval === "1m"}
          priceDecimals={2}
          className="w-full h-[21.25rem]"
        />
      )}
    </div>
  );
}

// 기관상세 컬럼 (이미지 순서: 금융투자·보험·기타금융·투신·사모펀드·연기금등·은행)
const ORG_COLS: { key: keyof MarketInvestorDay["breakdown"]; label: string }[] = [
  { key: "financialInvestmentEok", label: "금융투자" },
  { key: "insuranceEok", label: "보험" },
  { key: "otherFinanceEok", label: "기타금융" },
  { key: "trustEok", label: "투신" },
  { key: "privateEquityEok", label: "사모펀드" },
  { key: "pensionFundEok", label: "연기금등" },
  { key: "bankEok", label: "은행" },
];

// 기관상세 셀 좌우 패딩 — 그룹 경계선에 붙지 않게 첫·끝 셀만 바깥쪽 여백을 넓힘.
const orgPad = (i: number, len: number) =>
  `${i === 0 ? "pl-5" : "pl-2.5"} ${i === len - 1 ? "pr-5" : "pr-2.5"}`;

/** 순매수 숫자 — 부호색(+빨강/−파랑) · 천단위 · 축약 없음(억원 그대로). */
function NetNum({ eok }: { eok: number }) {
  const tone = eok > 0 ? "text-red-400" : eok < 0 ? "text-blue-400" : "text-zinc-600";
  const sign = eok > 0 ? "+" : eok < 0 ? "−" : "";
  return (
    <span className={`num ${tone}`}>
      {sign}
      {Math.abs(eok).toLocaleString("ko-KR")}
    </span>
  );
}

/** "2026-07-10" → "26년 7월 10일" */
function fmtDay(iso: string) {
  const [y, m, d] = iso.split("-");
  return `${y.slice(2)}년 ${Number(m)}월 ${Number(d)}일`;
}

/** 최근 10일 수급 — 키움 ka10051. 한 행에 개인·외국인·기관계 + 기관상세 7 + 기타법인. */
function RealInvestorTable({ market }: { market: MarketType }) {
  const { data, isLoading } = useMarketInvestorDaily(market, 10);
  const records = data ?? [];
  const edge = "border-l border-white/[0.06]"; // 기관상세 묶음 경계선

  return (
    <div className="px-1">
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>최근 10일 수급</span>
        <span className="text-xs text-zinc-600">순매수 · 억원</span>
      </div>
      {isLoading ? (
        <Skeleton className="h-48 w-full" />
      ) : records.length === 0 ? (
        <EmptyState message="일별 수급 데이터가 없습니다" />
      ) : (
        <div className="overflow-x-auto -mx-1 px-1">
          <table className="w-full text-xs whitespace-nowrap">
            <thead className="text-zinc-500">
              <tr>
                <th className="pb-1 pr-3" />
                <th className="text-right font-medium pb-1 px-2.5">개인</th>
                <th className="text-right font-medium pb-1 px-2.5">외국인</th>
                <th className="text-right font-medium pb-1 pl-2.5 pr-5">기관계</th>
                <th colSpan={ORG_COLS.length} className={`text-center font-medium pb-1.5 text-zinc-400 border-b border-white/[0.06] ${edge}`}>
                  기관상세
                </th>
                <th className={`text-right font-medium pb-1 pl-5 pr-2.5 ${edge}`}>기타법인</th>
              </tr>
              <tr>
                <th className="text-left font-medium pb-1.5 pr-3">일자</th>
                <th />
                <th />
                <th />
                {ORG_COLS.map((c, i) => (
                  <th key={c.key} className={`text-right font-medium pt-1.5 pb-1.5 ${orgPad(i, ORG_COLS.length)} ${i === 0 ? edge : ""}`}>
                    {c.label}
                  </th>
                ))}
                <th className={edge} />
              </tr>
            </thead>
            <tbody>
              {records.map((r) => (
                <tr
                  key={r.date}
                  className="[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-white/[0.02]"
                >
                  <td className="text-left text-zinc-400 num py-2 pr-3">{fmtDay(r.date)}</td>
                  <td className="text-right py-2 px-2.5">
                    <NetNum eok={r.individualEok} />
                  </td>
                  <td className="text-right py-2 px-2.5">
                    <NetNum eok={r.foreignEok} />
                  </td>
                  <td className="text-right py-2 pl-2.5 pr-5 font-medium">
                    <NetNum eok={r.institutionEok} />
                  </td>
                  {ORG_COLS.map((c, i) => (
                    <td key={c.key} className={`text-right py-2 ${orgPad(i, ORG_COLS.length)} ${i === 0 ? edge : ""}`}>
                      <NetNum eok={r.breakdown[c.key]} />
                    </td>
                  ))}
                  <td className={`text-right py-2 pl-5 pr-2.5 ${edge}`}>
                    <NetNum eok={r.otherCorpEok} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/** 세션별(오전/오후/막판) 순매수 — 당일 누적 스냅샷 경계 diff(구간별 증분). 스냅샷이 아직 없는 세션은 "집계 전". */
function RealSessionsCard({ market, date }: { market: MarketType; date: string }) {
  const { data, isLoading } = useMarketInvestorSessions(market, date);
  const list = data ?? [];
  const edge = "border-l border-white/[0.06]"; // 기관상세 묶음 경계선
  const numCols = 3 + ORG_COLS.length + 1; // 개인·외국인·기관계 + 기관상세 + 기타법인

  return (
    <div className="px-1">
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>정규장 시간대별 수급</span>
        <span className="text-xs text-zinc-600">오늘 · 억원</span>
      </div>
      {isLoading ? (
        <Skeleton className="h-32 w-full" />
      ) : (
        <div className="overflow-x-auto -mx-1 px-1">
          <table className="w-full text-xs whitespace-nowrap">
            <thead className="text-zinc-500">
              <tr>
                <th className="pb-1 pr-3" />
                <th className="text-right font-medium pb-1 px-2.5">개인</th>
                <th className="text-right font-medium pb-1 px-2.5">외국인</th>
                <th className="text-right font-medium pb-1 pl-2.5 pr-5">기관계</th>
                <th colSpan={ORG_COLS.length} className={`text-center font-medium pb-1.5 text-zinc-400 border-b border-white/[0.06] ${edge}`}>
                  기관상세
                </th>
                <th className={`text-right font-medium pb-1 pl-5 pr-2.5 ${edge}`}>기타법인</th>
              </tr>
              <tr>
                <th className="text-left font-medium pb-1.5 pr-3">시간대</th>
                <th />
                <th />
                <th />
                {ORG_COLS.map((c, i) => (
                  <th key={c.key} className={`text-right font-medium pt-1.5 pb-1.5 ${orgPad(i, ORG_COLS.length)} ${i === 0 ? edge : ""}`}>
                    {c.label}
                  </th>
                ))}
                <th className={edge} />
              </tr>
            </thead>
            <tbody>
              {list.map((s) => (
                <tr
                  key={s.name}
                  className="[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-white/[0.02]"
                >
                  <td className="text-left py-2 pr-3">
                    <div className="text-zinc-300">{s.name}</div>
                    <div className="text-[10px] text-zinc-600 num">{s.time}</div>
                  </td>
                  {s.nets == null ? (
                    <td colSpan={numCols} className="text-right py-2 px-2.5 text-zinc-600">
                      집계 전
                    </td>
                  ) : (
                    <>
                      <td className="text-right py-2 px-2.5">
                        <NetNum eok={s.nets.individual} />
                      </td>
                      <td className="text-right py-2 px-2.5">
                        <NetNum eok={s.nets.foreign} />
                      </td>
                      <td className="text-right py-2 pl-2.5 pr-5 font-medium">
                        <NetNum eok={s.nets.institution} />
                      </td>
                      {ORG_COLS.map((c, i) => (
                        <td key={c.key} className={`text-right py-2 ${orgPad(i, ORG_COLS.length)} ${i === 0 ? edge : ""}`}>
                          <NetNum eok={s.nets!.breakdown[c.key]} />
                        </td>
                      ))}
                      <td className={`text-right py-2 pl-5 pr-2.5 ${edge}`}>
                        <NetNum eok={s.nets.otherCorp} />
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function ThemeDetail({ theme, onStock }: { theme: CbTheme; onStock: (name: string) => void }) {
  const avg = theme.stocks.reduce((s, x) => s + x.pct, 0) / theme.stocks.length;
  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-start gap-3 pb-1">
        <span className="w-[42px] h-[42px] rounded-full bg-blue-500 text-white font-bold flex items-center justify-center text-sm shrink-0">
          {theme.name.slice(0, 2)}
        </span>
        <div className="flex-1 min-w-0">
          <span className="text-lg font-bold tracking-tight text-zinc-100">{theme.name} 테마</span>
          <div className="text-xs text-zinc-500 mt-0.5">
            {theme.stocks.length}개 종목 · {theme.news}
          </div>
        </div>
        <div className="text-right">
          <div className={`num text-xl font-bold ${colorByPnL(avg)}`}>{formatPct(avg / 100)}</div>
          <div className="text-xs text-zinc-600">테마 평균 등락</div>
        </div>
      </div>

      <div className={cardCls}>
        <div className="flex items-baseline justify-between mb-3">
          <span className={titleCls}>관련 지표 · ETF</span>
          <span className="text-xs text-zinc-600">DRAM · SOXX 등</span>
        </div>
        <div className="grid grid-cols-2 gap-2.5">
          {theme.related.map((nm) => {
            const r = relIndicator(nm);
            return (
              <div key={nm} className="rounded-xl bg-white/[0.02] p-3">
                <div className="text-xs text-zinc-400">{r.name}</div>
                <div className="num text-base font-bold text-zinc-100 mt-0.5">{formatPrice(r.value)}</div>
                <div className={`num text-[11px] ${colorByPnL(r.pct)}`}>{formatPct(r.pct / 100)}</div>
              </div>
            );
          })}
        </div>
      </div>

      <div className={cardCls}>
        <div className="flex items-baseline justify-between mb-2">
          <span className={titleCls}>테마 종목</span>
          <span className="text-xs text-zinc-600">{theme.stocks.length}</span>
        </div>
        <div className="flex flex-col">
          {theme.stocks.map((s) => (
            <button
              key={s.code}
              type="button"
              onClick={() => onStock(s.name)}
              className="flex items-center gap-2.5 py-2 border-t border-zinc-800/60 first:border-t-0 hover:bg-white/[0.03] transition-colors -mx-1 px-1 rounded-lg"
            >
              <StockAvatar name={s.name} code={s.code} size={26} />
              <span className="flex-1 text-left text-[13px] text-zinc-200">{s.name}</span>
              <span className="num text-[13px] font-semibold text-zinc-100">{formatPrice(s.price)}</span>
              <span className={`num text-[12px] w-16 text-right ${colorByPnL(s.pct)}`}>{formatPct(s.pct / 100)}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

// ============================================================
// 우: 뉴스 (선택 대상 따라감)
// ============================================================
function NewsPanel({ sel }: { sel: Selection }) {
  let label: string;
  let items: NewsItem[];
  if (sel.kind === "stock") {
    label = sel.name;
    items = stockNews(sel.name);
  } else if (sel.kind === "theme") {
    label = `${sel.name} 테마`;
    items = themeNews(themeByName[sel.name]);
  } else {
    label = "시장 · 재료";
    items = MARKET_NEWS;
  }
  return (
    <div className="min-h-0 flex flex-col overflow-hidden">
      <div className="px-4 pt-4 pb-2.5">
        <div className="text-base font-bold text-zinc-100">뉴스</div>
        <div className="text-[11px] text-zinc-500 mt-0.5">{label}</div>
      </div>
      <div className="flex-1 overflow-y-auto">
        {items.map((n, i) => (
          <button
            key={i}
            type="button"
            className="w-full text-left px-4 py-3 border-t border-white/[0.04] hover:bg-white/[0.03] transition-colors"
          >
            <div className="flex items-center gap-1.5 text-[11px] text-zinc-500 mb-1">
              <span className={`font-semibold ${n.hot ? "text-red-400" : "text-blue-500"}`}>{n.src}</span>
              <span>·</span>
              <span>{n.time}</span>
            </div>
            <div className="text-[13px] text-zinc-200 leading-snug">{n.headline}</div>
          </button>
        ))}
      </div>
    </div>
  );
}
