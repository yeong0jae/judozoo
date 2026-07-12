import { useMemo, useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  useFuturesCandles,
  useFuturesInvestorSessions,
  useFuturesQuote,
  useKospiIndex,
  useMarketCandles,
  useMarketInvestorDaily,
  useMarketInvestorSessions,
  useNasdaqFuturesCandles,
  useNasdaqFuturesQuote,
  useNasdaqIndexCandles,
  useNasdaqIndexQuote,
  useNightFuturesCandles,
  useNightFuturesQuote,
  useAddWatchStock,
  useCreateWatchTheme,
  useDeleteWatchTheme,
  useRemoveWatchStock,
  useStockSearch,
  useWatchThemeQuotes,
  useWatchThemes,
} from "../api/queries";
import CandleChart from "../components/common/CandleChart";
import StockDetailPanel from "../components/common/StockDetailPanel";
import { todayStr } from "../components/common/DateNavigator";
import EmptyState from "../components/common/EmptyState";
import Skeleton from "../components/common/Skeleton";
import StockAvatar from "../components/common/StockAvatar";
import { colorByPnL, formatEok, formatPct, formatPrice } from "../lib/format";
import type { FuturesOrgBreakdown, MarketInvestorDay, MarketType, WatchTheme } from "../types";
import {
  changeAmount,
  INDICES,
  investorDays,
  MARKET_NEWS,
  mockSeries,
  type CbIndex,
  type NewsItem,
  orgBreakdown,
  sessions,
  stockNews,
} from "../components/closingbet/mockData";
import { marketDailySeries, marketMinuteSeries } from "../components/common/tossCandles";

// 선택 대상 — 종목 / 테마 / 지수
type Selection =
  | { kind: "stock"; themeId: number; code: string; name: string }
  | { kind: "theme"; themeId: number }
  | { kind: "index"; id: string };

/** 실데이터(토스 Market Indicators) 연동이 끝난 지수 — 그 외는 아직 목데이터. */
const LIVE_MARKET: Record<string, MarketType> = { kospi: "KOSPI" };

/**
 * 전환 애니메이션 단위 — 테마 안에서 종목만 바꿀 땐 리마운트하지 않는다(왼쪽 종목 리스트가 깜빡이지 않게).
 */
const subjectKey = (sel: Selection) =>
  sel.kind === "index" ? `index-${sel.id}` : `theme-${sel.themeId}`;

/**
 * 시황분석 — 장 막판 매수 판단용 지표 집약 대시보드. 코스피는 실데이터(토스), 나머지는 목 데이터.
 * 좌: 테마 관심목록 / 중앙: 선택 대상(종목·테마·지수) 상세 / 우: 뉴스.
 */
export default function ClosingBetPage() {
  const [sel, setSel] = useState<Selection>({ kind: "index", id: "kospi" });
  const { data: themes = [] } = useWatchThemes();

  return (
    <div className="flex flex-col gap-4">
      <MarketStrip sel={sel} onSelect={(id) => setSel({ kind: "index", id })} />

      <div className="grid grid-cols-1 lg:grid-cols-[13rem_minmax(0,1fr)_18rem] gap-4 lg:h-[calc(100dvh-15rem)] lg:min-h-[40rem]">
        <ThemeWatchlist
          themes={themes}
          sel={sel}
          onTheme={(themeId) => setSel({ kind: "theme", themeId })}
        />
        <div className="min-h-0 lg:overflow-y-auto pr-1">
          <AnimatePresence mode="wait">
            <motion.div
              key={subjectKey(sel)}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
            >
              <SubjectDetail sel={sel} themes={themes} onSelect={setSel} />
            </motion.div>
          </AnimatePresence>
        </div>
        <NewsPanel sel={sel} themes={themes} />
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
  const futures = useFuturesQuote();
  const night = useNightFuturesQuote();
  const nasdaq = useNasdaqFuturesQuote();
  const nasdaqIndex = useNasdaqIndexQuote();
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 divide-x divide-y lg:divide-y-0 divide-white/[0.06]">
      {INDICES.map((ix) => {
        let value = ix.value;
        let pct = ix.pct;
        if (ix.id === "kospi") {
          value = kospi.data?.currentValue ?? value;
          pct = kospi.data?.changeRate ?? pct;
        } else if (ix.id === "kospiF") {
          value = futures.data?.futuresPrice ?? value;
          pct = futures.data?.changeRate ?? pct;
        } else if (ix.id === "nightF") {
          value = night.data?.price ?? value;
          pct = night.data?.changeRate ?? pct;
        } else if (ix.id === "nasF") {
          value = nasdaq.data?.price ?? value;
          pct = nasdaq.data?.changeRate ?? pct;
        } else if (ix.id === "nasdaq") {
          value = nasdaqIndex.data?.price ?? value;
          pct = nasdaqIndex.data?.changeRate ?? pct;
        }
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
// 좌: 테마 목록 — 종목은 상세 영역의 리스트에서 고른다(아코디언 없음).
// ============================================================
function ThemeWatchlist({
  themes,
  sel,
  onTheme,
}: {
  themes: WatchTheme[];
  sel: Selection;
  onTheme: (themeId: number) => void;
}) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const create = useCreateWatchTheme();
  const remove = useDeleteWatchTheme();

  const submit = () => {
    const trimmed = name.trim();
    if (!trimmed) return setAdding(false);
    create.mutate(trimmed, { onSuccess: () => setName("") });
    setAdding(false);
  };

  return (
    <div className="min-h-0 flex flex-col overflow-hidden">
      <div className="flex items-center justify-between px-3 pt-4 pb-2">
        <span className="text-base font-bold text-zinc-100">테마</span>
        <button
          type="button"
          onClick={() => setAdding(true)}
          className="p-1 rounded-md text-zinc-500 hover:bg-white/[0.06] hover:text-zinc-300"
          aria-label="테마 추가"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </button>
      </div>
      <div className="flex-1 overflow-y-auto px-1.5">
        {themes.map((t) => {
          const active = sel.kind !== "index" && sel.themeId === t.id;
          return (
            <div
              key={t.id}
              className={`group flex items-center justify-between pl-2.5 pr-1.5 py-2.5 rounded-xl transition-colors ${
                active ? "bg-blue-500/[0.08]" : "hover:bg-white/[0.03]"
              }`}
            >
              <button type="button" onClick={() => onTheme(t.id)} className="flex-1 flex items-baseline justify-between gap-2 text-left">
                <span className="text-[15px] font-medium text-zinc-300">{t.name}</span>
                <span className="text-xs text-zinc-500">{t.stocks.length}</span>
              </button>
              <button
                type="button"
                onClick={() => remove.mutate(t.id)}
                className="ml-1 p-0.5 rounded text-zinc-600 opacity-0 group-hover:opacity-100 hover:text-red-400"
                aria-label={`${t.name} 삭제`}
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            </div>
          );
        })}
        {adding && (
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            onBlur={submit}
            onKeyDown={(e) => {
              if (e.key === "Enter") submit();
              if (e.key === "Escape") {
                setName("");
                setAdding(false);
              }
            }}
            placeholder="테마 이름"
            className="w-full mt-1 rounded-xl bg-zinc-950/70 border border-white/[0.06] px-2.5 py-2 text-sm text-zinc-200 placeholder:text-zinc-600 outline-none focus:border-white/20"
          />
        )}
        {!themes.length && !adding && (
          <p className="px-2.5 py-6 text-xs text-zinc-600 leading-relaxed">
            테마가 없습니다.
            <br />+ 를 눌러 만들어보세요.
          </p>
        )}
      </div>
    </div>
  );
}

// ============================================================
// 중앙: 선택 대상 상세
// ============================================================
function SubjectDetail({
  sel,
  themes,
  onSelect,
}: {
  sel: Selection;
  themes: WatchTheme[];
  onSelect: (sel: Selection) => void;
}) {
  if (sel.kind === "index") {
    const ix = INDICES.find((i) => i.id === sel.id);
    if (!ix) return null;
    if (ix.id === "kospiF") return <FuturesIndexDetail index={ix} />;
    if (ix.id === "nightF") return <NightFuturesDetail index={ix} />;
    if (ix.id === "nasF") return <NasdaqFuturesDetail index={ix} />;
    if (ix.id === "nasdaq") return <NasdaqIndexDetail index={ix} />;
    const liveMarket = LIVE_MARKET[ix.id];
    return liveMarket ? <LiveIndexDetail market={liveMarket} fallback={ix} /> : <IndexDetail index={ix} />;
  }

  const theme = themes.find((t) => t.id === sel.themeId);
  if (!theme) return null;

  // 테마·종목은 [테마 종목 리스트 | 상세] 2열. 종목을 바꿔가며 눌러도 리스트가 남는다.
  return (
    <div className="grid grid-cols-1 md:grid-cols-[17rem_minmax(0,1fr)] gap-4">
      <ThemeStockList theme={theme} sel={sel} onSelect={onSelect} />
      {sel.kind === "stock" ? (
        <StockDetailPanel stockCode={sel.code} />
      ) : (
        <EmptyState message="종목을 선택하세요" />
      )}
    </div>
  );
}

/** 테마 종목 리스트 — 시세는 이 테마의 종목만 조회한다(키움 rate limit). */
function ThemeStockList({
  theme,
  sel,
  onSelect,
}: {
  theme: WatchTheme;
  sel: Selection;
  onSelect: (sel: Selection) => void;
}) {
  const codes = useMemo(() => theme.stocks.map((s) => s.stockCode), [theme.stocks]);
  const { data: quotes = [] } = useWatchThemeQuotes(codes);
  const quoteBy = useMemo(() => new Map(quotes.map((q) => [q.stockCode, q])), [quotes]);
  const remove = useRemoveWatchStock();
  const [adding, setAdding] = useState(false);

  return (
    <div className="flex flex-col min-w-0">
      <div className="flex items-center justify-between px-1 pb-2">
        <div className="flex items-baseline gap-2">
          <span className="text-sm font-bold text-zinc-100">{theme.name}</span>
          <span className="text-[11px] text-zinc-500">{theme.stocks.length}</span>
        </div>
        <button
          type="button"
          onClick={() => setAdding((v) => !v)}
          className="p-1 rounded-md text-zinc-500 hover:bg-white/[0.06] hover:text-zinc-300"
          aria-label="종목 추가"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </button>
      </div>

      {adding && <StockSearchBox themeId={theme.id} onDone={() => setAdding(false)} />}

      <div className="flex flex-col gap-0.5">
        {theme.stocks.map((s) => {
          const q = quoteBy.get(s.stockCode);
          const active = sel.kind === "stock" && sel.code === s.stockCode;
          return (
            <div
              key={s.stockCode}
              className={`group w-full flex items-center gap-2.5 px-2 py-2.5 rounded-xl transition-colors ${
                active ? "bg-blue-500/[0.08]" : "hover:bg-white/[0.03]"
              }`}
            >
              <button
                type="button"
                onClick={() => onSelect({ kind: "stock", themeId: theme.id, code: s.stockCode, name: s.stockName })}
                className="flex-1 min-w-0 flex items-center gap-2.5 text-left"
              >
                <StockAvatar name={s.stockName} code={s.stockCode} size={32} />
                <span className="flex-1 min-w-0 text-[13.5px] text-zinc-200 leading-tight line-clamp-2">{s.stockName}</span>
                <span className="text-right shrink-0">
                  <span className="block num text-[13.5px] font-semibold text-zinc-100">
                    {q ? formatPrice(q.currentPrice) : "—"}
                  </span>
                  <span className={`block num text-[11.5px] ${colorByPnL(q?.priceChangeRate ?? 0)}`}>
                    {q ? formatPct(q.priceChangeRate / 100) : ""}
                  </span>
                </span>
              </button>
              <button
                type="button"
                onClick={() => remove.mutate({ themeId: theme.id, stockCode: s.stockCode })}
                className="p-0.5 rounded text-zinc-600 opacity-0 group-hover:opacity-100 hover:text-red-400"
                aria-label={`${s.stockName} 삭제`}
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            </div>
          );
        })}
        {!theme.stocks.length && !adding && (
          <p className="px-2 py-6 text-xs text-zinc-600">+ 를 눌러 종목을 담아보세요.</p>
        )}
      </div>
    </div>
  );
}

/** 종목 검색 → 테마에 추가. 종목 카탈로그(stocks 테이블) 기준. */
function StockSearchBox({ themeId, onDone }: { themeId: number; onDone: () => void }) {
  const [query, setQuery] = useState("");
  const { data: results = [] } = useStockSearch(query);
  const add = useAddWatchStock();

  return (
    <div className="mb-2">
      <input
        autoFocus
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => e.key === "Escape" && onDone()}
        placeholder="종목명 검색"
        className="w-full rounded-xl bg-zinc-950/70 border border-white/[0.06] px-2.5 py-2 text-sm text-zinc-200 placeholder:text-zinc-600 outline-none focus:border-white/20"
      />
      {results.length > 0 && (
        <div className="mt-1 max-h-56 overflow-y-auto rounded-xl bg-zinc-950/70 border border-white/[0.06]">
          {results.map((r) => (
            <button
              key={r.stockCode}
              type="button"
              onClick={() => {
                add.mutate({ themeId, stockCode: r.stockCode, stockName: r.stockName });
                setQuery("");
                onDone();
              }}
              className="w-full flex items-baseline justify-between gap-2 px-2.5 py-2 text-left hover:bg-white/[0.04]"
            >
              <span className="text-[13px] text-zinc-200 truncate">{r.stockName}</span>
              <span className="num text-[11px] text-zinc-600">{r.stockCode}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
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
  decimal = false,
  chg: chgProp,
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
  decimal?: boolean;
  chg?: number; // 전일 대비를 API가 정확히 줄 때. 없으면 등락률에서 역산
}) {
  // 지수·선물은 소수 2자리(주가용 정수 반올림을 쓰면 1,210.50이 1,211로 뭉개짐)
  const chg = chgProp ?? (decimal ? price - price / (1 + pct / 100) : changeAmount(price, pct));
  const priceGroup = (
    <>
      <span className="num text-xl font-bold text-zinc-100">
        {decimal ? fmt2(price) : formatPrice(price)}
      </span>
      <span className={`num text-sm font-semibold ${colorByPnL(pct)}`}>
        {chg > 0 ? "+" : chg < 0 ? "−" : ""}
        {Math.abs(chg).toLocaleString("ko-KR", { maximumFractionDigits: decimal ? 2 : 0 })} (
        {formatPct(pct / 100)})
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

const INTERVAL_TABS: { key: ChartInterval; label: string }[] = [
  { key: "1m", label: "1분봉" },
  { key: "1d", label: "일봉" },
];

type ChartInterval = "1m" | "1d";

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
      <DetailHeader name={index.name} category="지수" price={index.value} pct={index.pct} priceInline decimal />
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
      <DetailHeader name={fallback.name} category="지수" price={value} pct={pct} priceInline decimal />
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

/** 코스피 선물 상세 — 종가베팅용. 헤더(선물가·베이시스·만기) + 베이시스 패널 + 차트. KIS 근월물 실시세. */
function FuturesIndexDetail({ index }: { index: CbIndex }) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  const { data } = useFuturesQuote();

  // 실데이터 우선, 없으면 목값(fallback)
  const futValue = data?.futuresPrice ?? index.value;
  const futPct = data?.changeRate ?? index.pct;
  const basis = data?.basis ?? 0.45; // 시장 베이시스 = 선물 − 현물(KOSPI200)
  const investors = data?.investors ?? null; // 투자자별 순매수(계약)
  const oi = data?.openInterest ?? 285432; // 미결제약정(계약)
  const oiChg = data?.openInterestChange ?? 3210; // 전일 대비 증감
  const expiryDate = data?.expiryDate ?? "2026-09-10"; // 만기일
  const dday = Math.max(
    0,
    Math.ceil((new Date(`${expiryDate}T00:00:00+09:00`).getTime() - Date.now()) / 86_400_000),
  ); // 만기까지 남은 일수
  const contango = basis >= 0;
  const tone = contango ? "text-red-400" : "text-blue-400";
  const badge = contango ? "bg-red-500/10 text-red-400" : "bg-blue-500/10 text-blue-400";
  const signed2 = (v: number) => `${v >= 0 ? "+" : "−"}${Math.abs(v).toFixed(2)}`;

  return (
    <div className="flex flex-col gap-4">
      <DetailHeader name={index.name} category="지수선물" price={futValue} pct={futPct} priceInline decimal />

      <div className="px-1">
        <div className="flex items-baseline justify-between mb-3">
          <span className={titleCls}>베이시스</span>
          <span className="text-xs text-zinc-600">선물 − 현물(KOSPI200)</span>
        </div>
        <div className="flex items-end gap-3 flex-wrap">
          <span className={`num text-2xl font-bold ${tone}`}>{signed2(basis)}</span>
          <span className={`mb-0.5 text-[11px] font-medium rounded-md px-2 py-0.5 ${badge}`}>
            {contango ? "콘탱고 · 선물 우위" : "백워데이션 · 현물 우위"}
          </span>
        </div>
      </div>

      {investors && (
        <div className="px-1">
          <div className="flex items-baseline justify-between mb-3">
            <span className={titleCls}>투자자 순매수</span>
            <span className="text-xs text-zinc-600">당일 누적 · 계약</span>
          </div>
          <div className="grid grid-cols-3 gap-2">
            {[
              { label: "외국인", value: investors.foreign },
              { label: "기관", value: investors.institution },
              { label: "개인", value: investors.individual },
            ].map((c) => (
              <div key={c.label} className="rounded-xl bg-white/[0.02] px-3 py-2.5">
                <div className="text-xs text-zinc-500">{c.label}</div>
                <div className={`num text-lg font-semibold mt-0.5 ${colorByPnL(c.value)}`}>
                  {c.value >= 0 ? "+" : "−"}
                  {Math.abs(c.value).toLocaleString("ko-KR")}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="px-1">
        <div className="flex items-baseline gap-2 text-sm">
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
          <span className="num text-xs text-zinc-500">D-{dday}</span>
        </div>
      </div>

      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>선물 차트</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <FuturesChart interval={chartInterval} />
      </div>

      <FuturesSessionsCard date={todayStr()} />
    </div>
  );
}

/**
 * 야간선물 상세(18:00~익일 06:00) — KIS 시장구분 CM.
 * 야간엔 코스피200 현물이 멈춰 있어 베이시스·괴리율·수급은 의미가 없어 싣지 않는다.
 * 대신 다음날 시초가를 가늠하는 값, 즉 직전 정규장 종가 대비 갭을 전면에 둔다.
 */
function NightFuturesDetail({ index }: { index: CbIndex }) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  const { data, isLoading } = useNightFuturesQuote();

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="야간선물 시세를 불러오지 못했습니다" />;

  const up = data.gap >= 0;
  const tone = up ? "text-red-400" : "text-blue-400";
  const badge = up ? "bg-red-500/10 text-red-400" : "bg-blue-500/10 text-blue-400";
  const signed2 = (v: number) => `${v >= 0 ? "+" : "−"}${Math.abs(v).toFixed(2)}`;

  return (
    <div className="flex flex-col gap-4">
      <DetailHeader
        name={index.name}
        category="야간선물"
        price={data.price}
        pct={data.changeRate}
        chg={data.gap}
        priceInline
        decimal
      />

      <div className="px-1">
        <div className="flex items-baseline justify-between mb-3">
          <span className={titleCls}>코스피200 선물 종가 대비 등락률</span>
          <span className="text-xs text-zinc-600">야간선물 − 코스피200 선물 종가</span>
        </div>
        <div className="flex items-end gap-3 flex-wrap">
          <span className={`num text-2xl font-bold ${tone}`}>{signed2(data.changeRate)}%</span>
          <span className={`num mb-0.5 text-sm font-semibold ${tone}`}>{signed2(data.gap)}</span>
          <span className={`mb-0.5 text-[11px] font-medium rounded-md px-2 py-0.5 ${badge}`}>
            {up ? "갭 상승 · 시초 강세 시사" : "갭 하락 · 시초 약세 시사"}
          </span>
        </div>
      </div>

      <div className="px-1">
        <div className="flex items-baseline gap-2 text-sm">
          <span className="text-xs text-zinc-500">미결제약정</span>
          <span className="num font-semibold text-zinc-100">
            {data.openInterest.toLocaleString("ko-KR")}
          </span>
          <span className="text-xs text-zinc-600">계약</span>
          <span className="num text-xs text-zinc-400">
            {data.openInterestChange >= 0 ? "+" : "−"}
            {Math.abs(data.openInterestChange).toLocaleString("ko-KR")} 전일
          </span>
        </div>
        <div className="mt-1.5 flex items-baseline gap-2 text-sm">
          <span className="text-xs text-zinc-500">거래량</span>
          <span className="num font-semibold text-zinc-100">
            {data.volume.toLocaleString("ko-KR")}
          </span>
          <span className="text-xs text-zinc-600">계약</span>
        </div>
      </div>

      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>야간선물 차트</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <NightFuturesChart interval={chartInterval} />
      </div>
    </div>
  );
}

/**
 * 나스닥100 선물(CME NQ) 상세 — 야후 파이낸스.
 * 대응 현물(나스닥100)이 우리 장중엔 닫혀 있어 베이시스·수급은 없다.
 * 우리 장중에 미국 심리가 어디로 기우는지 보는 용도라 등락률과 분봉이 전부다.
 */
function NasdaqFuturesDetail({ index }: { index: CbIndex }) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  const { data, isLoading } = useNasdaqFuturesQuote();

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="나스닥 선물 시세를 불러오지 못했습니다" />;

  return (
    <div className="flex flex-col gap-4">
      <DetailHeader
        name={index.name}
        category="지수선물"
        price={data.price}
        pct={data.changeRate}
        chg={data.priceChange}
        priceInline
        decimal
      />

      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>나스닥 선물 차트</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <NasdaqFuturesChart interval={chartInterval} />
      </div>
    </div>
  );
}

/** 나스닥 선물 1분봉(최근 2일)/일봉(6개월) — 야후 캔들. */
function NasdaqFuturesChart({ interval }: { interval: ChartInterval }) {
  const { data, isLoading } = useNasdaqFuturesCandles(interval);
  const items = data ?? [];
  const series = useMemo(
    () => (interval === "1d" ? marketDailySeries(items) : marketMinuteSeries(items)),
    [items, interval],
  );
  if (isLoading) return <Skeleton className="h-[21.25rem] w-full" />;
  if (!items.length) return <EmptyState message="캔들 데이터가 없습니다" />;
  return (
    <CandleChart
      key={`nasf-${interval}`}
      series={series}
      timeVisible={interval === "1m"}
      priceDecimals={2}
      className="w-full h-[21.25rem]"
    />
  );
}

/**
 * 나스닥 종합지수(^IXIC) 상세 — 야후 파이낸스.
 * 현물이라 미 정규장(23:30~06:00 KST)에만 움직인다. 우리 장중엔 직전 마감가에 멈춰 있다.
 */
function NasdaqIndexDetail({ index }: { index: CbIndex }) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  const { data, isLoading } = useNasdaqIndexQuote();

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="나스닥 지수를 불러오지 못했습니다" />;

  return (
    <div className="flex flex-col gap-4">
      <DetailHeader
        name={index.name}
        category="지수"
        price={data.price}
        pct={data.changeRate}
        chg={data.priceChange}
        priceInline
        decimal
      />

      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>나스닥 차트</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <NasdaqIndexChart interval={chartInterval} />
      </div>
    </div>
  );
}

/** 나스닥 지수 1분봉(최근 2일)/일봉(6개월) — 야후 캔들. */
function NasdaqIndexChart({ interval }: { interval: ChartInterval }) {
  const { data, isLoading } = useNasdaqIndexCandles(interval);
  const items = data ?? [];
  const series = useMemo(
    () => (interval === "1d" ? marketDailySeries(items) : marketMinuteSeries(items)),
    [items, interval],
  );
  if (isLoading) return <Skeleton className="h-[21.25rem] w-full" />;
  if (!items.length) return <EmptyState message="캔들 데이터가 없습니다" />;
  return (
    <CandleChart
      key={`nasdaq-${interval}`}
      series={series}
      timeVisible={interval === "1m"}
      priceDecimals={2}
      className="w-full h-[21.25rem]"
    />
  );
}

/** 야간선물 1분봉(최근 세션)/일봉 — KIS 근월물 캔들. */
function NightFuturesChart({ interval }: { interval: ChartInterval }) {
  const { data, isLoading } = useNightFuturesCandles(interval);
  const items = data ?? [];
  const series = useMemo(
    () => (interval === "1d" ? marketDailySeries(items) : marketMinuteSeries(items)),
    [items, interval],
  );
  if (isLoading) return <Skeleton className="h-[21.25rem] w-full" />;
  if (!items.length) {
    return (
      <EmptyState
        message={interval === "1m" ? "야간 세션 분봉이 없습니다" : "일봉 데이터가 없습니다"}
      />
    );
  }
  return (
    <CandleChart
      key={`night-${interval}`}
      series={series}
      timeVisible={interval === "1m"}
      priceDecimals={2}
      className="w-full h-[21.25rem]"
    />
  );
}

/**
 * 선물 세션별(오전/오후/막판) 순매수 — 당일 누적 스냅샷의 경계 diff(구간별 증분).
 * 폴러가 적재한 스냅샷이 있어야 하므로, 아직 없는 세션은 "집계 전".
 */
function FuturesSessionsCard({ date }: { date: string }) {
  const { data, isLoading } = useFuturesInvestorSessions(date);
  const list = data ?? [];
  const edge = "border-l border-white/[0.06]"; // 기관상세 묶음 경계선
  const numCols = 3 + FUTURES_ORG_COLS.length + 1; // 개인·외국인·기관계 + 기관상세 + 기타법인

  return (
    <div className="px-1">
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>정규장 시간대별 수급</span>
        <span className="text-xs text-zinc-600">오늘 · 계약</span>
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
                <th colSpan={FUTURES_ORG_COLS.length} className={`text-center font-medium pb-1.5 text-zinc-400 border-b border-white/[0.06] ${edge}`}>
                  기관상세
                </th>
                <th className={`text-right font-medium pb-1 pl-5 pr-2.5 ${edge}`}>기타법인</th>
              </tr>
              <tr>
                <th className="text-left font-medium pb-1.5 pr-3">시간대</th>
                <th />
                <th />
                <th />
                {FUTURES_ORG_COLS.map((c, i) => (
                  <th key={c.key} className={`text-right font-medium pt-1.5 pb-1.5 ${orgPad(i, FUTURES_ORG_COLS.length)} ${i === 0 ? edge : ""}`}>
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
                      {FUTURES_ORG_COLS.map((c, i) => (
                        <td
                          key={c.key}
                          className={`text-right py-2 ${orgPad(i, FUTURES_ORG_COLS.length)} ${i === 0 ? edge : ""}`}
                        >
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

// 선물 기관상세 — KIS 선물 분류(현물의 금융투자↔증권, 연기금등↔기금, 기타금융↔종금에 대응).
const FUTURES_ORG_COLS: { key: keyof FuturesOrgBreakdown; label: string }[] = [
  { key: "securities", label: "증권" },
  { key: "insurance", label: "보험" },
  { key: "merchantBank", label: "종금" },
  { key: "trust", label: "투신" },
  { key: "privateEquity", label: "사모펀드" },
  { key: "fund", label: "기금" },
  { key: "bank", label: "은행" },
  { key: "otherOrg", label: "기타단체" },
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

// ============================================================
// 우: 뉴스 (선택 대상 따라감)
// ============================================================
function NewsPanel({ sel, themes }: { sel: Selection; themes: WatchTheme[] }) {
  let label: string;
  let items: NewsItem[];
  if (sel.kind === "stock") {
    label = sel.name;
    items = stockNews(sel.name);
  } else if (sel.kind === "theme") {
    label = `${themes.find((t) => t.id === sel.themeId)?.name ?? ""} 테마`;
    items = MARKET_NEWS; // 사용자 정의 테마라 테마별 목뉴스가 없다
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
