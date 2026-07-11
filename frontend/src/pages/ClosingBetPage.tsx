import { useMemo, useState } from "react";
import {
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
import type { MarketInvestorRecord, MarketInvestorSession, MarketType } from "../types";
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
import { marketDailySeries, marketMinuteSeries } from "../components/closingbet/tossCandles";

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
  const [sel, setSel] = useState<Selection>({ kind: "stock", name: "SK하이닉스" });
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
          <SubjectDetail sel={sel} onStock={(name) => setSel({ kind: "stock", name })} />
        </div>
        <NewsPanel sel={sel} />
      </div>
    </div>
  );
}

// ============================================================
// 상단 시장 스트립 — 지수/선물 카드 + 분위기 메모
// ============================================================
const stripCardCls = (active: boolean) =>
  `flex flex-col gap-0.5 px-3.5 py-2.5 min-w-[10rem] rounded-xl border text-left transition-colors ${
    active ? "border-blue-500 bg-blue-500/[0.08]" : "border-zinc-800 bg-zinc-900 hover:border-zinc-700"
  }`;

function MarketStrip({ sel, onSelect }: { sel: Selection; onSelect: (id: string) => void }) {
  return (
    <div className="flex items-stretch gap-2.5 overflow-x-auto pb-1">
      {INDICES.map((ix) => {
        const active = sel.kind === "index" && sel.id === ix.id;
        if (ix.id === "kospi") {
          return <KospiStripCard key={ix.id} active={active} fallback={ix} onSelect={() => onSelect(ix.id)} />;
        }
        return (
          <button key={ix.id} type="button" onClick={() => onSelect(ix.id)} className={stripCardCls(active)}>
            <span className="text-xs text-zinc-400">{ix.name}</span>
            <span className="num text-lg font-bold text-zinc-100">{formatPrice(ix.value)}</span>
            <span className={`num text-xs ${colorByPnL(ix.pct)}`}>{formatPct(ix.pct / 100)}</span>
          </button>
        );
      })}
      <div className="flex-1 min-w-[16rem] flex flex-col gap-1 px-4 py-2.5 rounded-xl border border-zinc-800 bg-zinc-900">
        <span className="text-[11px] text-zinc-500 tracking-wide">오늘 시장 분위기 · 재료</span>
        <div
          contentEditable
          suppressContentEditableWarning
          className="text-sm text-zinc-200 outline-none leading-relaxed empty:before:content-[attr(data-ph)] empty:before:text-zinc-600"
          data-ph="글로벌·정책·테마 재료를 메모…"
        >
          엔비디아 실적 서프라이즈 → 반도체 수급 지속. 정부 전력망 투자 발표(연속성 ○). 조선 수주 모멘텀 유지.
        </div>
      </div>
    </div>
  );
}

/** 코스피 카드 — 실데이터(useKospiIndex). 로딩 중엔 목데이터로 폴백해 깜빡임 방지. */
function KospiStripCard({
  active,
  onSelect,
  fallback,
}: {
  active: boolean;
  onSelect: () => void;
  fallback: CbIndex;
}) {
  const { data } = useKospiIndex();
  const value = data?.currentValue ?? fallback.value;
  const pct = data?.changeRate ?? fallback.pct;
  return (
    <button type="button" onClick={onSelect} className={stripCardCls(active)}>
      <span className="text-xs text-zinc-400">{fallback.name}</span>
      <span className="num text-lg font-bold text-zinc-100">{formatPrice(value)}</span>
      <span className={`num text-xs ${colorByPnL(pct)}`}>{formatPct(pct / 100)}</span>
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
    <div className="min-h-0 flex flex-col rounded-2xl bg-zinc-900 overflow-hidden">
      <div className="px-4 pt-4 pb-2">
        <span className="text-base font-bold text-zinc-100">테마</span>
      </div>
      <div className="px-4 pb-2">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="종목 검색"
          className="w-full rounded-xl bg-zinc-950 border border-zinc-800 px-3 py-2 text-sm text-zinc-200 placeholder:text-zinc-600 outline-none focus:border-zinc-600"
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
                className={`flex items-center justify-between pl-3.5 pr-2 py-2 border-l-2 ${
                  themeSel ? "border-blue-500 bg-blue-500/[0.08]" : "border-transparent hover:bg-white/[0.03]"
                }`}
              >
                <button type="button" onClick={() => onTheme(t.name)} className="flex-1 flex items-baseline gap-2 text-left">
                  <span className={`text-sm font-bold ${themeSel ? "text-blue-500" : "text-zinc-100"}`}>{t.name}</span>
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
                  {!q && (
                    <div className="mx-3.5 mb-1 flex items-center gap-2 rounded-lg bg-zinc-950 border border-white/[0.04] px-2.5 py-1.5">
                      <span className="text-[10px] font-bold text-blue-500 shrink-0">AI</span>
                      <span className="text-[11px] text-zinc-400 leading-snug">{t.news}</span>
                    </div>
                  )}
                  {rows.map((s) => {
                    const active = sel.kind === "stock" && sel.name === s.name;
                    return (
                      <button
                        key={s.code}
                        type="button"
                        onClick={() => onStock(s.name)}
                        className={`w-full flex items-center gap-2.5 pl-3.5 pr-4 py-2 border-l-2 text-left ${
                          active ? "border-blue-500 bg-blue-500/[0.08]" : "border-transparent hover:bg-white/[0.03]"
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
    return ix ? <IndexDetail index={ix} /> : null;
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

function ChartCard({ chartKey, subtitle }: { chartKey: string; subtitle: string }) {
  const [minute, setMinute] = useState(true);
  const series = useMemo(() => mockSeries(chartKey, minute), [chartKey, minute]);
  return (
    <div className={cardCls}>
      <div className="flex items-center justify-between mb-3">
        <span className={titleCls}>
          차트 <span className="text-zinc-600 font-normal">· {subtitle}</span>
        </span>
        <div className="flex rounded-xl bg-white/[0.04] p-0.5 text-xs shrink-0">
          {[
            { k: true, label: "1분봉" },
            { k: false, label: "일봉" },
          ].map((o) => (
            <button
              key={o.label}
              type="button"
              onClick={() => setMinute(o.k)}
              className={`px-3 py-1.5 rounded-lg transition-colors ${
                minute === o.k ? "bg-white/[0.1] text-zinc-100 font-medium" : "text-zinc-500 hover:text-zinc-300"
              }`}
            >
              {o.label}
            </button>
          ))}
        </div>
      </div>
      <CandleChart key={`${chartKey}-${minute}`} series={series} timeVisible={minute} className="w-full h-72" />
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
          <div key={s.name} className="rounded-xl bg-zinc-950 border border-zinc-800 p-3">
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
  const chg = changeAmount(stock.price, stock.pct);
  const hi = highDistance(name);
  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-start gap-3 pb-1">
        <StockAvatar name={name} code={stock.code} size={42} />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-lg font-bold tracking-tight text-zinc-100">{name}</span>
            <span className="num text-xs text-zinc-500 border border-zinc-800 rounded px-1.5 py-0.5">{stock.code}</span>
          </div>
          <div className="text-xs text-zinc-500 mt-0.5">{theme} · KOSPI</div>
          <span className="inline-flex items-center gap-1.5 mt-1.5 text-xs bg-zinc-900 border border-zinc-800 rounded-lg px-2.5 py-1">
            <span className="text-zinc-500">신고가까지</span>
            <span className="num text-blue-400 font-medium">{hi.toFixed(1)}%</span>
            <span className="text-zinc-600">· 52주</span>
          </span>
        </div>
        <div className="text-right">
          <div className="num text-xl font-bold text-zinc-100">{formatPrice(stock.price)}</div>
          <div className={`num text-sm ${colorByPnL(stock.pct)}`}>
            {chg > 0 ? "+" : chg < 0 ? "−" : ""}
            {Math.abs(chg).toLocaleString("ko-KR")} ({formatPct(stock.pct / 100)})
          </div>
        </div>
      </div>

      <ChartCard chartKey={name} subtitle={theme} />
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
        <InvestorTable dataKey={name} />
        <TradingValueCard dataKey={name} />
      </div>
      <SessionsCard dataKey={name} />
      <NxtCard dataKey={name} />
    </div>
  );
}

function IndexDetail({ index }: { index: CbIndex }) {
  const liveMarket = LIVE_MARKET[index.id];
  if (liveMarket) return <LiveIndexDetail market={liveMarket} fallback={index} />;

  return (
    <div className="flex flex-col gap-4">
      <IndexHeader name={index.name} value={index.value} pct={index.pct} />
      <ChartCard chartKey={index.id} subtitle="지수" />
      <InvestorTable dataKey={index.id} />
      <SessionsCard dataKey={index.id} />
    </div>
  );
}

function IndexHeader({ name, value, pct }: { name: string; value: number; pct: number }) {
  const chg = changeAmount(value, pct);
  return (
    <div className="flex items-start gap-3 pb-1">
      <span className="w-[42px] h-[42px] rounded-full bg-blue-500 text-white font-bold flex items-center justify-center text-sm shrink-0">
        {name.slice(0, 2)}
      </span>
      <div className="flex-1 min-w-0">
        <span className="text-lg font-bold tracking-tight text-zinc-100">{name}</span>
        <div className="text-xs text-zinc-500 mt-0.5">지수</div>
      </div>
      <div className="text-right">
        <div className="num text-xl font-bold text-zinc-100">{formatPrice(value)}</div>
        <div className={`num text-sm ${colorByPnL(pct)}`}>
          {chg > 0 ? "+" : chg < 0 ? "−" : ""}
          {Math.abs(chg).toLocaleString("ko-KR")} ({formatPct(pct / 100)})
        </div>
      </div>
    </div>
  );
}

/** 실데이터 지수 상세(코스피) — 지수값·분봉·일봉·10일 수급·세션 전부 토스. */
function LiveIndexDetail({ market, fallback }: { market: MarketType; fallback: CbIndex }) {
  const date = todayStr();
  const priceQ = useKospiIndex();
  const value = priceQ.data?.currentValue ?? fallback.value;
  const pct = priceQ.data?.changeRate ?? fallback.pct;

  return (
    <div className="flex flex-col gap-4">
      <IndexHeader name={fallback.name} value={value} pct={pct} />
      <LiveIndexChartCard market={market} />
      <RealInvestorTable market={market} />
      <RealSessionsCard market={market} date={date} />
    </div>
  );
}

/** 지수 분봉/일봉 — 토스 캔들(OHLCV) 그대로 캔들차트. */
function LiveIndexChartCard({ market }: { market: MarketType }) {
  const [interval, setInterval] = useState<"1m" | "1d">("1m");
  const { data, isLoading } = useMarketCandles(market, interval);
  const items = data ?? [];
  const series = useMemo(
    () => (interval === "1d" ? marketDailySeries(items) : marketMinuteSeries(items)),
    [items, interval],
  );
  return (
    <div className={cardCls}>
      <div className="flex items-center justify-between mb-3">
        <span className={titleCls}>차트</span>
        <div className="flex rounded-xl bg-white/[0.04] p-0.5 text-xs shrink-0">
          {[
            { k: "1m" as const, label: "1분봉" },
            { k: "1d" as const, label: "일봉" },
          ].map((o) => (
            <button
              key={o.label}
              type="button"
              onClick={() => setInterval(o.k)}
              className={`px-3 py-1.5 rounded-lg transition-colors ${
                interval === o.k ? "bg-white/[0.1] text-zinc-100 font-medium" : "text-zinc-500 hover:text-zinc-300"
              }`}
            >
              {o.label}
            </button>
          ))}
        </div>
      </div>
      {isLoading ? (
        <Skeleton className="h-72 w-full" />
      ) : items.length === 0 ? (
        <div className="h-72 flex items-center justify-center">
          <EmptyState message={interval === "1m" ? "장중에 1분봉이 표시됩니다" : "일봉 데이터가 없습니다"} />
        </div>
      ) : (
        <CandleChart
          key={`${market}-${interval}`}
          series={series}
          timeVisible={interval === "1m"}
          priceDecimals={2}
          className="w-full h-72"
        />
      )}
    </div>
  );
}

const REAL_INVESTOR_LABELS: { key: keyof MarketInvestorRecord["breakdown"]; label: string }[] = [
  { key: "pensionFundEok", label: "연기금" },
  { key: "trustEok", label: "투신" },
  { key: "financialInvestmentEok", label: "금융투자" },
  { key: "privateEquityEok", label: "사모" },
  { key: "insuranceEok", label: "보험" },
  { key: "bankEok", label: "은행" },
  { key: "otherFinanceEok", label: "기타금융" },
];

function NetLabel({ label, eok }: { label: string; eok: number }) {
  return (
    <span className="text-zinc-500">
      {label} <Amount eok={eok} />
    </span>
  );
}

/** 기관 세부 순매수 한 줄 — 항목 수만큼 균등 컬럼(7개=토스, 6개=키움). 라벨 위·금액 아래. */
function InstitutionBreakdownRow({ items }: { items: { label: string; eok: number }[] }) {
  return (
    <div
      className="grid gap-1.5"
      style={{ gridTemplateColumns: `repeat(${items.length}, minmax(0,1fr))` }}
    >
      {items.map((it) => (
        <div key={it.label} className="rounded-md bg-zinc-800/40 px-2 py-1.5 text-center">
          <div className="text-xs text-zinc-500">{it.label}</div>
          <div className="num text-[13px] font-semibold mt-0.5">
            <Amount eok={it.eok} />
          </div>
        </div>
      ))}
    </div>
  );
}

/** 최근 10일 수급 — 토스 투자자별 매매대금. 일자마다 개인·외국인·기관·기타법인 + 기관 7세부(상시). */
function RealInvestorTable({ market }: { market: MarketType }) {
  const { data, isLoading } = useMarketInvestorDaily(market, 10);
  const records = data ?? [];

  return (
    <div className={cardCls}>
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>최근 10일 수급</span>
        <span className="text-xs text-zinc-600">억원</span>
      </div>
      {isLoading ? (
        <Skeleton className="h-48 w-full" />
      ) : records.length === 0 ? (
        <EmptyState message="일별 수급 데이터가 없습니다" />
      ) : (
        <div className="flex flex-col gap-3">
          {records.map((r) => (
            <div key={r.date} className="border-t border-zinc-800/60 pt-2.5 first:border-t-0 first:pt-0">
              <div className="flex items-center justify-between mb-1.5">
                <span className="num text-[13px] font-semibold text-zinc-300">{r.date.slice(5)}</span>
                <div className="flex gap-4 text-xs">
                  <NetLabel label="개인" eok={r.individualNetEok} />
                  <NetLabel label="외국인" eok={r.foreignNetEok} />
                  <NetLabel label="기관" eok={r.institutionNetEok} />
                  <NetLabel label="기타법인" eok={r.otherCorpNetEok} />
                </div>
              </div>
              <InstitutionBreakdownRow
                items={REAL_INVESTOR_LABELS.map(({ key, label }) => ({ label, eok: r.breakdown[key] }))}
              />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

const SESSION_ROWS: { key: keyof NonNullable<MarketInvestorSession["nets"]>; label: string }[] = [
  { key: "individual", label: "개인" },
  { key: "foreign", label: "외인" },
  { key: "institution", label: "기관" },
  { key: "otherCorp", label: "기타법인" },
];

/** 세션별(오전/오후/막판) 순매수 — 당일 누적 스냅샷 경계 diff. 스냅샷이 아직 없는 세션은 "집계 전". */
function RealSessionsCard({ market, date }: { market: MarketType; date: string }) {
  const { data, isLoading } = useMarketInvestorSessions(market, date);
  const list = data ?? [];

  return (
    <div className={cardCls}>
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>정규장 시간대별 수급</span>
        <span className="text-xs text-zinc-600">오늘 · 억원</span>
      </div>
      {isLoading ? (
        <Skeleton className="h-32 w-full" />
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
          {list.map((s) => (
            <div key={s.name} className="rounded-xl bg-zinc-950 border border-zinc-800 p-3">
              <div className="flex items-start justify-between mb-2">
                <div>
                  <div className="text-xs text-zinc-300">{s.name}</div>
                  <div className="text-[10px] text-zinc-600 num">{s.time}</div>
                </div>
                {s.name === "막판 동시호가" && (
                  <span className="text-[9px] text-blue-500 font-semibold">종가 결정</span>
                )}
              </div>
              {s.nets == null ? (
                <div className="text-[11px] text-zinc-600 py-1">집계 전</div>
              ) : (
                SESSION_ROWS.map(({ key, label }) => (
                  <div key={key} className="flex justify-between text-xs py-0.5">
                    <span className="text-zinc-500">{label}</span>
                    <Amount eok={s.nets![key]} />
                  </div>
                ))
              )}
            </div>
          ))}
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
              <div key={nm} className="rounded-xl bg-zinc-950 border border-zinc-800 p-3">
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
              className="flex items-center gap-2.5 py-2 border-t border-zinc-800/60 first:border-t-0 hover:bg-white/[0.03] -mx-1 px-1 rounded-lg"
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
    <div className="min-h-0 flex flex-col rounded-2xl bg-zinc-900 overflow-hidden">
      <div className="px-4 pt-4 pb-2.5">
        <div className="text-base font-bold text-zinc-100">뉴스</div>
        <div className="text-[11px] text-zinc-500 mt-0.5">{label}</div>
      </div>
      <div className="flex-1 overflow-y-auto">
        {items.map((n, i) => (
          <button
            key={i}
            type="button"
            className="w-full text-left px-4 py-3 border-t border-white/[0.04] hover:bg-white/[0.03]"
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
