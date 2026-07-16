import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  useDailyCandles,
  useFuturesCandles,
  useFuturesInvestorDaily,
  useFuturesInvestorSessions,
  useFuturesQuote,
  useKosdaqIndex,
  useKospiIndex,
  useMinuteCandles,
  useStockInvestorDaily,
  useStockNews,
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
  useRenameWatchTheme,
  useReorderWatchStocks,
  useReorderWatchThemes,
  useStockSearch,
  useWatchThemeQuotes,
  useWatchThemes,
} from "../api/queries";
import CandleChart, { dailySeries, minuteSeries } from "../components/common/CandleChart";
import OverseasStockDetailPanel from "../components/common/OverseasStockDetailPanel";
import { todayStr } from "../components/common/DateNavigator";
import EmptyState from "../components/common/EmptyState";
import Skeleton from "../components/common/Skeleton";
import StockAvatar from "../components/common/StockAvatar";
import { colorByPnL, formatPct, formatPrice } from "../lib/format";
import type { FuturesOrgBreakdown, MarketInvestorDay, MarketType, WatchTheme } from "../types";
import { marketDailySeries, marketMinuteSeries } from "../components/common/tossCandles";

// 선택 대상 — 종목 / 테마 / 지수
type Selection =
  | { kind: "stock"; themeId: number; code: string; name: string; exchange: string | null }
  | { kind: "theme"; themeId: number }
  | { kind: "index"; id: string };

const DEFAULT_SELECTION: Selection = { kind: "index", id: "kospi" };
const SELECTION_KEY = "market-analysis:selection";

/** 다른 메뉴를 다녀와도 보던 대상을 그대로 연다. 저장값이 깨졌으면 코스피로. */
function loadSelection(): Selection {
  try {
    const raw = localStorage.getItem(SELECTION_KEY);
    if (!raw) return DEFAULT_SELECTION;
    const parsed = JSON.parse(raw) as Selection;
    return parsed?.kind ? parsed : DEFAULT_SELECTION;
  } catch {
    return DEFAULT_SELECTION;
  }
}

/** 상단 스트립 지수 — 값은 전부 API에서 온다. 여기엔 이름·라우팅만 둔다. */
type IndexInfo = { id: string; name: string; delayed?: boolean };
const INDICES: IndexInfo[] = [
  { id: "kospi", name: "코스피" },
  { id: "kosdaq", name: "코스닥" },
  { id: "kospiF", name: "코스피 선물" },
  // CME 무료 시세는 10분 지연 배포라 야후도 그만큼 늦은 값을 준다(실시간은 유료 피드만).
  { id: "nasF", name: "나스닥 선물", delayed: true },
  { id: "nightF", name: "야간 선물" },
  { id: "nasdaq", name: "나스닥" },
];

/** 실시간이 아닌 시세임을 알리는 배지. */
function DelayBadge() {
  return (
    <span className="text-[10px] text-zinc-500 bg-white/[0.04] rounded px-1 py-px">10분 지연</span>
  );
}

/**
 * 전환 애니메이션 단위 — 테마 안에서 종목만 바꿀 땐 리마운트하지 않는다(왼쪽 종목 리스트가 깜빡이지 않게).
 */
/** 드래그앤드롭 결과 — [from]을 [to] 자리로 옮긴 새 배열. */
function move<T>(list: T[], from: number, to: number): T[] {
  const next = [...list];
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item);
  return next;
}

const subjectKey = (sel: Selection) =>
  sel.kind === "index" ? `index-${sel.id}` : `theme-${sel.themeId}`;

/**
 * 시황분석 — 장 막판 매수 판단용 지표 집약 대시보드.
 * 좌: 테마 관심목록 / 중앙: 선택 대상(종목·테마·지수) 상세 / 우: 종목 뉴스(종목을 골랐을 때만).
 */
export default function ClosingBetPage() {
  const [sel, setSel] = useState<Selection>(loadSelection);
  const { data: themes = [] } = useWatchThemes();
  useEffect(() => {
    localStorage.setItem(SELECTION_KEY, JSON.stringify(sel));
  }, [sel]);
  // 저장해둔 테마·종목이 그 사이 지워졌을 수 있다 — 테마가 사라졌으면 코스피로 되돌린다.
  useEffect(() => {
    if (sel.kind === "index" || !themes.length) return;
    const theme = themes.find((t) => t.id === sel.themeId);
    const gone =
      !theme || (sel.kind === "stock" && !theme.stocks.some((s) => s.stockCode === sel.code));
    if (gone) setSel(DEFAULT_SELECTION);
  }, [themes, sel]);
  // 지수·테마엔 뉴스가 없다(KIS 뉴스 API는 종목코드로만 조회된다). 상세는 그대로 꽉 찬 넓이를 쓴다.
  const news = sel.kind === "stock" ? sel : null;

  // 좌측 사이드바 드릴다운: 지수를 보면 테마 리스트, 테마·종목을 고르면 그 테마의 종목 리스트.
  // 뒤로 가기는 마지막으로 보던 지수로 돌아간다.
  const lastIndexId = useRef("kospi");
  useEffect(() => {
    if (sel.kind === "index") lastIndexId.current = sel.id;
  }, [sel]);
  const activeTheme = sel.kind !== "index" ? themes.find((t) => t.id === sel.themeId) : undefined;

  return (
    <div className="flex flex-col gap-4">
      <MarketStrip sel={sel} onSelect={(id) => setSel({ kind: "index", id })} />

      <div
        className={`grid grid-cols-1 gap-4 lg:h-[calc(100dvh-15rem)] lg:min-h-[40rem] ${
          news
            ? "lg:grid-cols-[17rem_minmax(0,1fr)_24rem]"
            : "lg:grid-cols-[17rem_minmax(0,1fr)]"
        }`}
      >
        <div className="min-h-0 lg:overflow-hidden relative">
          <AnimatePresence mode="wait" initial={false}>
            {sel.kind === "index" || !activeTheme ? (
              <motion.div
                key="themes"
                initial={{ opacity: 0, x: -16 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -16 }}
                transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
                className="h-full min-h-0"
              >
                <ThemeWatchlist
                  themes={themes}
                  sel={sel}
                  onTheme={(themeId) => setSel({ kind: "theme", themeId })}
                />
              </motion.div>
            ) : (
              <motion.div
                key="stocks"
                initial={{ opacity: 0, x: 16 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 16 }}
                transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
                className="h-full min-h-0 lg:overflow-y-auto pr-1"
              >
                <ThemeStockList
                  theme={activeTheme}
                  sel={sel}
                  onSelect={setSel}
                  onBack={() => setSel({ kind: "index", id: lastIndexId.current })}
                />
              </motion.div>
            )}
          </AnimatePresence>
        </div>
        <div className="min-h-0 lg:overflow-hidden">
          <AnimatePresence mode="wait">
            <motion.div
              key={subjectKey(sel)}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
              className="h-full min-h-0"
            >
              <SubjectDetail sel={sel} />
            </motion.div>
          </AnimatePresence>
        </div>
        {news && (
          <StockNewsPanel key={news.code} code={news.code} exchange={news.exchange} />
        )}
      </div>
    </div>
  );
}

// ============================================================
// 상단 시장 스트립 — 지수/선물 카드 + 분위기 메모
// ============================================================
/** 전일 대비 등락금액(원) 근사 — 등락률로 역산. 주가는 정수라 전일 종가를 반올림한다. */
function changeAmount(price: number, pct: number): number {
  const prev = Math.round(price / (1 + pct / 100));
  return price - prev;
}

const fmt2 = (v: number) =>
  v.toLocaleString("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const chgText = (value: number, pct: number) => {
  const chg = value - value / (1 + pct / 100);
  const sign = chg > 0 ? "+" : chg < 0 ? "−" : "";
  return `${sign}${Math.abs(chg).toLocaleString("ko-KR", { maximumFractionDigits: 2 })} (${formatPct(pct / 100)})`;
};

/** 값이 아직 안 온 칸은 대시로 둔다(목값을 보여줬다 실값으로 바뀌면 오독한다). */
const toCell = (value?: number, pct?: number) =>
  value === undefined || pct === undefined ? null : { value, pct };

/** 상단 시장 스트립 — 배경 없이 페이지에 얹히고, 동일폭 6칸을 얇은 구분선으로만 분리. */
function MarketStrip({ sel, onSelect }: { sel: Selection; onSelect: (id: string) => void }) {
  const kospi = useKospiIndex();
  const kosdaq = useKosdaqIndex();
  const futures = useFuturesQuote();
  const night = useNightFuturesQuote();
  const nasdaq = useNasdaqFuturesQuote();
  const nasdaqIndex = useNasdaqIndexQuote();
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 divide-x divide-y lg:divide-y-0 divide-white/[0.06]">
      {INDICES.map((ix) => {
        const q =
          ix.id === "kospi" ? toCell(kospi.data?.currentValue, kospi.data?.changeRate)
          : ix.id === "kosdaq" ? toCell(kosdaq.data?.currentValue, kosdaq.data?.changeRate)
          : ix.id === "kospiF" ? toCell(futures.data?.futuresPrice, futures.data?.changeRate)
          : ix.id === "nightF" ? toCell(night.data?.price, night.data?.changeRate)
          : ix.id === "nasF" ? toCell(nasdaq.data?.price, nasdaq.data?.changeRate)
          : toCell(nasdaqIndex.data?.price, nasdaqIndex.data?.changeRate);
        return (
          <IndexCell
            key={ix.id}
            ix={ix}
            value={q?.value ?? null}
            pct={q?.pct ?? null}
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
  ix: IndexInfo;
  value: number | null;
  pct: number | null;
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
      <span className="flex items-center gap-1.5 text-[14px] font-medium text-zinc-300">
        {ix.name}
        {ix.delayed && <DelayBadge />}
      </span>
      <div className="flex items-baseline gap-1.5 flex-wrap mt-0.5">
        {value === null || pct === null ? (
          <span className="num text-[17px] font-bold text-zinc-700">—</span>
        ) : (
          <>
            <span className="num text-[17px] font-bold text-zinc-100">{fmt2(value)}</span>
            <span className={`num text-[14px] font-medium ${colorByPnL(pct)}`}>{chgText(value, pct)}</span>
          </>
        )}
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
  const [dragIdx, setDragIdx] = useState<number | null>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editName, setEditName] = useState("");
  const create = useCreateWatchTheme();
  const rename = useRenameWatchTheme();
  const remove = useDeleteWatchTheme();
  const reorder = useReorderWatchThemes();

  const submit = () => {
    const trimmed = name.trim();
    if (!trimmed) return setAdding(false);
    create.mutate(trimmed, { onSuccess: () => setName("") });
    setAdding(false);
  };

  const startRename = (themeId: number, current: string) => {
    setEditingId(themeId);
    setEditName(current);
  };

  /** 빈 이름·같은 이름이면 서버를 부르지 않고 편집만 닫는다. */
  const submitRename = () => {
    const trimmed = editName.trim();
    const target = themes.find((t) => t.id === editingId);
    if (trimmed && target && trimmed !== target.name) {
      rename.mutate({ themeId: target.id, name: trimmed });
    }
    setEditingId(null);
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
        {themes.map((t, i) => {
          const active = sel.kind !== "index" && sel.themeId === t.id;
          if (editingId === t.id) {
            return (
              <input
                key={t.id}
                autoFocus
                value={editName}
                onChange={(e) => setEditName(e.target.value)}
                onBlur={submitRename}
                onKeyDown={(e) => {
                  if (e.key === "Enter") submitRename();
                  if (e.key === "Escape") setEditingId(null);
                }}
                className="w-full mt-1 rounded-xl bg-zinc-950/70 border border-white/[0.06] px-2.5 py-2 text-sm text-zinc-200 outline-none focus:border-white/20"
              />
            );
          }
          return (
            <div
              key={t.id}
              draggable
              onDragStart={() => setDragIdx(i)}
              onDragOver={(e) => e.preventDefault()}
              onDrop={() => {
                if (dragIdx === null || dragIdx === i) return;
                reorder.mutate(move(themes, dragIdx, i).map((x) => x.id));
                setDragIdx(null);
              }}
              onDragEnd={() => setDragIdx(null)}
              className={`group flex items-center justify-between pl-2.5 pr-1.5 py-2.5 rounded-xl transition-colors cursor-grab active:cursor-grabbing ${
                dragIdx === i ? "opacity-40" : ""
              } ${active ? "bg-blue-500/[0.08]" : "hover:bg-white/[0.03]"}`}
            >
              <button
                type="button"
                onClick={() => onTheme(t.id)}
                onDoubleClick={() => startRename(t.id, t.name)}
                className="flex-1 flex items-baseline justify-between gap-2 text-left"
              >
                <span className="text-[15px] font-medium text-zinc-300">{t.name}</span>
                <span className="text-xs text-zinc-500">{t.stocks.length}</span>
              </button>
              <button
                type="button"
                onClick={() => startRename(t.id, t.name)}
                className="ml-1 p-0.5 rounded text-zinc-600 opacity-0 group-hover:opacity-100 hover:text-zinc-300"
                aria-label={`${t.name} 이름 변경`}
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
                  <path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
                </svg>
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
function SubjectDetail({ sel }: { sel: Selection }) {
  if (sel.kind === "index") {
    const ix = INDICES.find((i) => i.id === sel.id);
    if (!ix) return null;
    const detail =
      ix.id === "kospiF" ? <FuturesIndexDetail index={ix} />
      : ix.id === "nightF" ? <NightFuturesDetail index={ix} />
      : ix.id === "nasF" ? <NasdaqFuturesDetail index={ix} />
      : ix.id === "nasdaq" ? <NasdaqIndexDetail index={ix} />
      : ix.id === "kosdaq" ? <LiveIndexDetail market="KOSDAQ" name={ix.name} />
      : <LiveIndexDetail market="KOSPI" name={ix.name} />;
    return <div className="h-full lg:overflow-y-auto pr-1">{detail}</div>;
  }

  // 종목 리스트는 좌측 사이드바로 옮겼다. 여기선 선택한 종목의 상세만 보여준다.
  return (
    <div className="h-full lg:overflow-y-auto pr-1">
      {sel.kind === "stock" ? (
        sel.exchange ? (
          <OverseasStockDetailPanel exchange={sel.exchange} symbol={sel.code} chartOnly />
        ) : (
          <WatchStockDetail themeId={sel.themeId} code={sel.code} name={sel.name} />
        )
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
  onBack,
}: {
  theme: WatchTheme;
  sel: Selection;
  onSelect: (sel: Selection) => void;
  onBack: () => void;
}) {
  const { data: quotes = [] } = useWatchThemeQuotes(theme.id, theme.stocks.length > 0);
  const quoteBy = useMemo(() => new Map(quotes.map((q) => [q.stockCode, q])), [quotes]);
  const remove = useRemoveWatchStock();
  const reorder = useReorderWatchStocks();
  const [adding, setAdding] = useState(false);
  const [dragIdx, setDragIdx] = useState<number | null>(null);

  return (
    <div className="flex flex-col min-w-0">
      <div className="flex items-center justify-between px-1 pb-2">
        <button
          type="button"
          onClick={onBack}
          className="flex items-baseline gap-2 min-w-0 text-left rounded-md -ml-1 pl-1 pr-1.5 py-0.5 hover:bg-white/[0.04]"
          aria-label="테마 목록으로"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" className="self-center shrink-0 text-zinc-500">
            <path d="M15 18l-6-6 6-6" />
          </svg>
          <span className="text-sm font-bold text-zinc-100 truncate">{theme.name}</span>
          <span className="text-[11px] text-zinc-500 shrink-0">{theme.stocks.length}</span>
        </button>
        <button
          type="button"
          onClick={() => setAdding((v) => !v)}
          className="p-1 rounded-md text-zinc-500 hover:bg-white/[0.06] hover:text-zinc-300 shrink-0"
          aria-label="종목 추가"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </button>
      </div>

      {adding && <StockSearchBox themeId={theme.id} onDone={() => setAdding(false)} />}

      <div className="flex flex-col gap-0.5">
        {theme.stocks.map((s, i) => {
          const q = quoteBy.get(s.stockCode);
          const active = sel.kind === "stock" && sel.code === s.stockCode;
          return (
            <div
              key={s.stockCode}
              draggable
              onDragStart={() => setDragIdx(i)}
              onDragOver={(e) => e.preventDefault()}
              onDrop={() => {
                if (dragIdx === null || dragIdx === i) return;
                reorder.mutate({
                  themeId: theme.id,
                  stockCodes: move(theme.stocks, dragIdx, i).map((x) => x.stockCode),
                });
                setDragIdx(null);
              }}
              onDragEnd={() => setDragIdx(null)}
              className={`group w-full flex items-center gap-2.5 px-2 py-2.5 rounded-xl transition-colors cursor-grab active:cursor-grabbing ${
                dragIdx === i ? "opacity-40" : ""
              } ${active ? "bg-blue-500/[0.08]" : "hover:bg-white/[0.03]"}`}
            >
              <button
                type="button"
                onClick={() =>
                  onSelect({
                    kind: "stock",
                    themeId: theme.id,
                    code: s.stockCode,
                    name: s.stockName,
                    exchange: s.exchange,
                  })
                }
                className="flex-1 min-w-0 flex items-center gap-2.5 text-left"
              >
                <StockAvatar name={s.stockName} code={s.stockCode} size={32} />
                <span className="flex-1 min-w-0 text-[13.5px] text-zinc-200 leading-tight line-clamp-2">{s.stockName}</span>
                <span className="text-right shrink-0">
                  <span className="block num text-[13.5px] font-semibold text-zinc-100">
                    {q ? (q.overseas ? `$${q.currentPrice.toFixed(2)}` : formatPrice(q.currentPrice)) : "—"}
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
                add.mutate({
                  themeId,
                  stockCode: r.stockCode,
                  stockName: r.stockName,
                  exchange: r.exchange,
                });
                setQuery("");
                onDone();
              }}
              className="w-full flex items-baseline justify-between gap-2 px-2.5 py-2 text-left hover:bg-white/[0.04]"
            >
              <span className="flex items-baseline gap-1.5 min-w-0">
                <span className="text-[13px] text-zinc-200 truncate">{r.stockName}</span>
                {r.exchange && (
                  <span className="shrink-0 text-[10px] text-blue-400 bg-blue-500/10 rounded px-1 py-px">
                    {r.exchange}
                  </span>
                )}
              </span>
              <span className="num text-[11px] text-zinc-600">{r.stockCode}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

const titleCls = "text-sm font-semibold text-zinc-400";

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

function LiveIndexDetail({ market, name }: { market: MarketType; name: string }) {
  const date = todayStr();
  const kospiQ = useKospiIndex();
  const kosdaqQ = useKosdaqIndex();
  const priceQ = market === "KOSPI" ? kospiQ : kosdaqQ;
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");

  if (priceQ.isLoading) return <Skeleton className="h-96 w-full" />;
  if (!priceQ.data) return <EmptyState message="지수를 불러오지 못했습니다" />;
  const value = priceQ.data.currentValue;
  const pct = priceQ.data.changeRate;

  return (
    <div className="flex flex-col gap-4">
      <DetailHeader name={name} category="지수" price={value} pct={pct} priceInline decimal />
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
 * 시황분석 종목 상세 — 지수 상세와 같은 짜임(차트 + 최근 10일 수급).
 * 주도주·시그널 로그가 쓰는 공용 StockDetailPanel과 달리 '상세' 탭이 없다.
 */
function WatchStockDetail({
  themeId,
  code,
  name,
}: {
  themeId: number;
  code: string;
  name: string;
}) {
  const date = todayStr();
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  // 테마 시세 폴링과 같은 쿼리 키라 추가 호출 없이 캐시를 함께 쓴다.
  const { data: quotes = [] } = useWatchThemeQuotes(themeId, true);
  const quote = quotes.find((q) => q.stockCode === code);

  return (
    <div className="flex flex-col gap-4">
      <DetailHeader
        name={name}
        code={code}
        category="종목"
        price={quote?.currentPrice ?? 0}
        pct={quote?.priceChangeRate ?? 0}
        priceInline
      />
      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>종목 차트</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <WatchStockChart code={code} date={date} interval={chartInterval} />
      </div>
      <StockInvestorTable stockCode={code} />
    </div>
  );
}

/** 종목 1분봉/일봉 — 주도주 상세와 같은 캔들 소스(키움). */
function WatchStockChart({
  code,
  date,
  interval,
}: {
  code: string;
  date: string;
  interval: ChartInterval;
}) {
  const minuteQ = useMinuteCandles(interval === "1m" ? code : null, date);
  const dailyQ = useDailyCandles(interval === "1d" ? code : null, date);
  const q = interval === "1m" ? minuteQ : dailyQ;
  const items = q.data ?? [];

  if (q.isLoading) return <Skeleton className="h-[21.25rem] w-full" />;
  if (items.length === 0) {
    return (
      <div className="h-[21.25rem] flex items-center justify-center">
        <EmptyState message={interval === "1m" ? "분봉 데이터가 없습니다" : "일봉 데이터가 없습니다"} />
      </div>
    );
  }
  return (
    <CandleChart
      key={`${code}-${interval}`}
      series={
        interval === "1m"
          ? minuteSeries(minuteQ.data ?? [])
          : dailySeries(dailyQ.data ?? [])
      }
      timeVisible={interval === "1m"}
      className="w-full h-[21.25rem]"
    />
  );
}

/** 코스피 선물 상세 — 종가베팅용. 헤더(선물가·베이시스·만기) + 베이시스 패널 + 차트. KIS 근월물 실시세. */
function FuturesIndexDetail({ index }: { index: IndexInfo }) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  const { data, isLoading } = useFuturesQuote();

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="선물 시세를 불러오지 못했습니다" />;

  const futValue = data.futuresPrice;
  const futPct = data.changeRate;
  const basis = data.basis; // 시장 베이시스 = 선물 − 현물(KOSPI200)
  const oi = data.openInterest;
  const oiChg = data.openInterestChange;
  const expiryDate = data.expiryDate;
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
      <FuturesDailyCard />
    </div>
  );
}

/**
 * 야간선물 상세(18:00~익일 06:00) — KIS 시장구분 CM.
 * 야간엔 코스피200 현물이 멈춰 있어 베이시스·괴리율·수급은 의미가 없어 싣지 않는다.
 * 대신 다음날 시초가를 가늠하는 값, 즉 직전 정규장 종가 대비 갭을 전면에 둔다.
 */
function NightFuturesDetail({ index }: { index: IndexInfo }) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  const { data, isLoading } = useNightFuturesQuote();

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="야간선물 시세를 불러오지 못했습니다" />;

  const up = data.gap >= 0;
  const tone = up ? "text-red-400" : "text-blue-400";
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
function NasdaqFuturesDetail({ index }: { index: IndexInfo }) {
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
        extra={<DelayBadge />}
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
function NasdaqIndexDetail({ index }: { index: IndexInfo }) {
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
  const keyOf = (session: string, field: string) => `flowdelta:futures:${date}:${session}:${field}`;
  useEffect(() => pruneFlowDelta(date), [date]);

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
              {list.map((s) => {
                const active = isActiveSession(s.time, date === todayStr());
                return (
                  <tr
                    key={s.name}
                    className={`[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-white/[0.02]`}
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
                          <FlowNum eok={s.nets.individual} storageKey={keyOf(s.name, "individual")} active={active} />
                        </td>
                        <td className="text-right py-2 px-2.5">
                          <FlowNum eok={s.nets.foreign} storageKey={keyOf(s.name, "foreign")} active={active} />
                        </td>
                        <td className="text-right py-2 pl-2.5 pr-5 font-medium">
                          <FlowNum eok={s.nets.institution} storageKey={keyOf(s.name, "institution")} active={active} />
                        </td>
                        {FUTURES_ORG_COLS.map((c, i) => (
                          <td
                            key={c.key}
                            className={`text-right py-2 ${orgPad(i, FUTURES_ORG_COLS.length)} ${i === 0 ? edge : ""}`}
                          >
                            <FlowNum eok={s.nets!.breakdown[c.key]} storageKey={keyOf(s.name, c.key)} active={active} />
                          </td>
                        ))}
                        <td className={`text-right py-2 pl-5 pr-2.5 ${edge}`}>
                          <FlowNum eok={s.nets.otherCorp} storageKey={keyOf(s.name, "otherCorp")} active={active} />
                        </td>
                      </>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/**
 * 선물 최근 10일 수급 — 거래일별 마지막 스냅샷(= 그날의 당일 누적).
 * 선물엔 일별 조회 API가 없어 폴러가 쌓은 스냅샷으로만 만든다. 적재 시작 전 과거는 소급되지 않는다.
 */
function FuturesDailyCard() {
  const { data, isLoading } = useFuturesInvestorDaily(10);
  const records = data ?? [];
  const edge = "border-l border-white/[0.06]"; // 기관상세 묶음 경계선

  return (
    <div className="px-1">
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>최근 10일 수급</span>
        <span className="text-xs text-zinc-600">순매수 · 계약</span>
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
                <th colSpan={FUTURES_ORG_COLS.length} className={`text-center font-medium pb-1.5 text-zinc-400 border-b border-white/[0.06] ${edge}`}>
                  기관상세
                </th>
                <th className={`text-right font-medium pb-1 pl-5 pr-2.5 ${edge}`}>기타법인</th>
              </tr>
              <tr>
                <th className="text-left font-medium pb-1.5 pr-3">일자</th>
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
              {records.map((r) => (
                <tr
                  key={r.date}
                  className="[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-white/[0.02]"
                >
                  <td className="text-left text-zinc-400 num py-2 pr-3">{fmtDay(r.date)}</td>
                  <td className="text-right py-2 px-2.5">
                    <NetNum eok={r.nets.individual} />
                  </td>
                  <td className="text-right py-2 px-2.5">
                    <NetNum eok={r.nets.foreign} />
                  </td>
                  <td className="text-right py-2 pl-2.5 pr-5 font-medium">
                    <NetNum eok={r.nets.institution} />
                  </td>
                  {FUTURES_ORG_COLS.map((c, i) => (
                    <td key={c.key} className={`text-right py-2 ${orgPad(i, FUTURES_ORG_COLS.length)} ${i === 0 ? edge : ""}`}>
                      <NetNum eok={r.nets.breakdown[c.key]} />
                    </td>
                  ))}
                  <td className={`text-right py-2 pl-5 pr-2.5 ${edge}`}>
                    <NetNum eok={r.nets.otherCorp} />
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

// 셀별 마지막 값·변화량을 localStorage에 담아 새로고침 후에도 복원한다. 키에 날짜가 있어 날이 바뀌면 자연 초기화.
function loadCell(key: string): { v: number; d: number } | null {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}
function saveCell(key: string, v: number, d: number) {
  try {
    localStorage.setItem(key, JSON.stringify({ v, d }));
  } catch {
    // 저장 실패(용량·프라이빗 모드)는 무시 — 델타는 부가 정보라 없어도 값은 정상.
  }
}

/** 오늘 날짜가 안 든 flowdelta 잔재를 청소한다(날이 바뀌면 어제 변화량 제거). */
function pruneFlowDelta(date: string) {
  for (let i = localStorage.length - 1; i >= 0; i--) {
    const k = localStorage.key(i);
    if (k?.startsWith("flowdelta:") && !k.includes(`:${date}:`)) localStorage.removeItem(k);
  }
}

/** 지금 진행 중인 시간대인지 — 오늘이고 현재 시각이 그 구간("HH:MM~HH:MM") 안일 때만. */
function isActiveSession(time: string, isToday: boolean): boolean {
  if (!isToday) return false;
  const [start, end] = time.split("~");
  if (!start || !end) return false;
  const now = new Date();
  const hm = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
  return hm >= start && hm < end;
}

/**
 * 순매수 숫자 + 직전 폴 대비 변화량. 각 칸이 자기 값 변화를 직접 추적한다.
 * 변화량은 [active](지금 진행 중인 시간대)일 때만 표시한다 — 완료된 과거 구간은 값이 고정이라 의미 없다.
 * 마지막 변화량은 오른쪽에 흐리게 계속 남고(새로고침해도 localStorage에서 복원), 값이 또 바뀌면 다시 깜빡인다.
 */
function FlowNum({ eok, storageKey, active }: { eok: number; storageKey: string; active: boolean }) {
  const init = useRef<{ v: number; d: number }>(null as unknown as { v: number; d: number });
  if (init.current == null) init.current = loadCell(storageKey) ?? { v: eok, d: 0 };
  const prevRef = useRef(init.current.v);
  const seqRef = useRef(0);
  const [last, setLast] = useState<{ delta: number; seq: number } | null>(
    init.current.d !== 0 ? { delta: init.current.d, seq: 0 } : null,
  );
  useEffect(() => {
    if (!active) return;
    const d = eok - prevRef.current;
    if (d === 0) return;
    prevRef.current = eok;
    seqRef.current += 1;
    setLast({ delta: d, seq: seqRef.current });
    saveCell(storageKey, eok, d);
  }, [eok, storageKey, active]);
  return (
    <span className="inline-flex items-baseline justify-end gap-1.5">
      <NetNum eok={eok} />
      {active && last && <DeltaFlash key={last.seq} delta={last.delta} />}
    </span>
  );
}

/** 변화량 배지 — key(seq)로 재마운트될 때마다 flow-delta 애니메이션이 다시 재생된다. */
function DeltaFlash({ delta }: { delta: number }) {
  const tone = delta > 0 ? "text-red-400" : "text-blue-400";
  return (
    <span className={`flow-delta num text-[10px] ${tone}`}>
      {delta > 0 ? "+" : "−"}
      {Math.abs(delta).toLocaleString("ko-KR")}
    </span>
  );
}

/** "2026-07-10" → "26년 7월 10일" */
function fmtDay(iso: string) {
  const [y, m, d] = iso.split("-");
  return `${y.slice(2)}년 ${Number(m)}월 ${Number(d)}일`;
}

/** 시장 최근 10일 수급 — 키움 ka10051. */
function RealInvestorTable({ market }: { market: MarketType }) {
  const { data, isLoading } = useMarketInvestorDaily(market, 10);
  return <InvestorDailyTable records={data ?? []} isLoading={isLoading} />;
}

/** 종목 최근 10일 수급 — 키움 ka10059. 시장 표와 같은 구성이라 표를 공유한다. */
function StockInvestorTable({ stockCode }: { stockCode: string }) {
  const { data, isLoading } = useStockInvestorDaily(stockCode, 10);
  return <InvestorDailyTable records={data ?? []} isLoading={isLoading} />;
}

/** 최근 10일 수급 표 — 한 행에 개인·외국인·기관계 + 기관상세 7 + 기타법인. 시장·종목 공용. */
function InvestorDailyTable({
  records,
  isLoading,
}: {
  records: MarketInvestorDay[];
  isLoading: boolean;
}) {
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
  const keyOf = (session: string, field: string) => `flowdelta:${market}:${date}:${session}:${field}`;
  useEffect(() => pruneFlowDelta(date), [date]);

  return (
    <div className="px-1">
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>시간대별 수급</span>
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
              {list.map((s) => {
                const active = isActiveSession(s.time, date === todayStr());
                return (
                  <tr
                    key={s.name}
                    className={`[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-white/[0.02]`}
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
                          <FlowNum eok={s.nets.individual} storageKey={keyOf(s.name, "individual")} active={active} />
                        </td>
                        <td className="text-right py-2 px-2.5">
                          <FlowNum eok={s.nets.foreign} storageKey={keyOf(s.name, "foreign")} active={active} />
                        </td>
                        <td className="text-right py-2 pl-2.5 pr-5 font-medium">
                          <FlowNum eok={s.nets.institution} storageKey={keyOf(s.name, "institution")} active={active} />
                        </td>
                        {ORG_COLS.map((c, i) => (
                          <td key={c.key} className={`text-right py-2 ${orgPad(i, ORG_COLS.length)} ${i === 0 ? edge : ""}`}>
                            <FlowNum eok={s.nets!.breakdown[c.key]} storageKey={keyOf(s.name, c.key)} active={active} />
                          </td>
                        ))}
                        <td className={`text-right py-2 pl-5 pr-2.5 ${edge}`}>
                          <FlowNum eok={s.nets.otherCorp} storageKey={keyOf(s.name, "otherCorp")} active={active} />
                        </td>
                      </>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ============================================================
// 우: 뉴스 (종목을 골랐을 때만)
// ============================================================

/** "2026-07-16T15:35:00" → ["7월 16일", "15:35"] (날짜/시간 두 줄용). */
function fmtNewsTime(iso: string): [string, string] {
  const d = new Date(iso);
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  return [`${d.getMonth() + 1}월 ${d.getDate()}일`, `${hh}:${mm}`];
}

/**
 * 종목 관련 뉴스 — KIS(국내는 종합 시황/공시, 해외는 해외뉴스종합). 둘 다 제목만 오고 원문 링크는 없다.
 * 공시는 국내 목록에만 섞여 온다.
 */
function StockNewsPanel({ code, exchange }: { code: string; exchange: string | null }) {
  const { data, isLoading } = useStockNews(code, exchange);
  const items = data ?? [];

  return (
    <aside className="min-h-0 flex flex-col overflow-hidden">
      <div className="flex items-center justify-between px-3 pt-4 pb-2">
        <span className="text-base font-bold text-zinc-100">관련 뉴스</span>
        <span className="text-xs text-zinc-600">뉴스 · 공시</span>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-1.5">
        {isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : items.length === 0 ? (
          <EmptyState message="관련 뉴스가 없습니다" />
        ) : (
          items.map((n) => (
            /* 원문 URL을 주지 않는 API라, 제목을 그대로 구글에 검색해 원문을 찾아가게 한다. */
            <a
              key={n.seqNo}
              href={`https://www.google.com/search?q=${encodeURIComponent(n.title)}`}
              target="_blank"
              rel="noopener noreferrer"
              className="flex gap-3 px-2.5 py-3 border-b border-white/[0.04] transition-colors hover:bg-white/[0.02]"
            >
              <span className="shrink-0 w-14 pt-0.5 num text-[11px] text-zinc-500 leading-tight whitespace-nowrap">
                <span className="block">{fmtNewsTime(n.publishedAt)[0]}</span>
                <span className="block">{fmtNewsTime(n.publishedAt)[1]}</span>
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-sm leading-snug text-zinc-100">{n.title}</p>
                <div className="mt-1.5 flex items-center gap-1.5">
                  <span className="inline-flex items-center gap-1 text-[11px] text-zinc-400 bg-white/[0.04] rounded px-1.5 py-0.5">
                    <span className="w-1 h-1 rounded-full bg-red-400" />
                    {n.source}
                  </span>
                  {n.disclosure && (
                    <span className="text-[11px] text-zinc-400 bg-white/[0.04] rounded px-1.5 py-0.5">
                      공시
                    </span>
                  )}
                </div>
              </div>
            </a>
          ))
        )}
      </div>
    </aside>
  );
}
