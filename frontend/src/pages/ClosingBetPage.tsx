import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Link, Navigate, useParams, useSearchParams } from "react-router-dom";
import { useMe } from "../api/auth";
import GoogleLoginButton from "../components/common/GoogleLoginButton";
import {
  INDICES,
  loadLastSlug,
  rememberSlug,
  type IndexInfo,
} from "../lib/indices";
import { AnimatePresence, motion } from "motion/react";
import {
  useFuturesCandles,
  useFuturesInvestorDaily,
  useFuturesInvestorSessions,
  useFuturesQuote,
  useKosdaqIndex,
  useKospiIndex,
  useMarketCandles,
  useMarketInvestorDaily,
  useMarketInvestorSessions,
  useNasdaqFuturesCandles,
  useNasdaqFuturesQuote,
  useNasdaqIndexCandles,
  useMacroCandles,
  useMacroQuotes,
  useNasdaqIndexQuote,
  useNightFuturesCandles,
  useNightFuturesQuote,
} from "../api/queries";
import CandleChart from "../components/common/CandleChart";
import HolidayBanner from "../components/common/HolidayBanner";
import { todayStr } from "../components/common/DateNavigator";
import EmptyState from "../components/common/EmptyState";
import Skeleton from "../components/common/Skeleton";
import { colorByPnL, formatPct } from "../lib/format";
import type {
  FuturesNets,
  FuturesOrgBreakdown,
  MacroQuote,
  MacroTarget,
  MarketCandleItem,
  MarketInvestorDay,
  MarketType,
} from "../types";
import { marketDailySeries, marketMinuteSeries } from "../components/common/tossCandles";

/**
 * 실시간이 아닌 시세임을 알리는 배지.
 *
 * 회색이면 라벨에 묻혀 안 읽힌다 — "지금 값이 아니다"는 놓치면 오독으로 이어지는 정보다.
 * 색은 테마의 배지 패턴(`bg-X-50` + `text-X-700`)을 쓰고, 그중 경고 자리인 amber를 고른다
 * (파랑은 "장중" 칩이, 빨강·파랑은 손익이 이미 쓴다).
 */
function DelayBadge() {
  return (
    <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[11px] font-medium text-amber-700">
      10분 지연
    </span>
  );
}

/**
 * 지수·수급 — 왼쪽 지수 목록(모바일은 가로 칩) + 오른쪽 선택 지수 상세.
 *
 * 목록이 세로라 매크로 네 지표도 각자 한 줄을 갖는다. 예전 가로 스트립은 여덟 칸 중
 * 한 칸에 넷을 욱여넣어 좁은 화면에서 줄이 접히고 스트립 전체가 높아졌다.
 */
function ClosingBetPageInner({ ix, authenticated }: { ix: IndexInfo; authenticated: boolean }) {
  const [params] = useSearchParams();
  const macro = MACRO_ITEMS.find((m) => m.key === params.get("t")) ?? MACRO_ITEMS[0];
  const groups = useIndexGroups(ix.slug, macro.key);

  useEffect(() => {
    rememberSlug(ix.slug);
  }, [ix.slug]);

  return (
    <div className="flex flex-col gap-4 lg:flex-row lg:gap-0 lg:h-[calc(100dvh-6.5rem)] lg:min-h-[40rem]">
      <aside className="hidden lg:block w-72 shrink-0 overflow-y-auto border-r border-zinc-800 pr-3">
        <div className="flex flex-col gap-1.5 px-3 pb-1">
          <h1 className="text-[20px] font-bold text-zinc-100">지수·수급</h1>
          <HolidayBanner />
        </div>
        <IndexList groups={groups} />
      </aside>
      <div className="flex flex-col gap-3 lg:hidden">
        <HolidayBanner />
        <IndexChips groups={groups} />
      </div>

      <div className="min-w-0 flex-1 lg:overflow-hidden lg:pl-8">
        <AnimatePresence mode="wait">
          <motion.div
            key={`${ix.slug}:${ix.id === "macro" ? macro.key : ""}`}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
            className="h-full min-h-0"
          >
            <SubjectDetail ix={ix} macro={macro} authenticated={authenticated} />
          </motion.div>
        </AnimatePresence>
      </div>
    </div>
  );
}

// ============================================================
// 지수 목록 — 데스크톱은 왼쪽 세로 목록, 모바일은 가로 칩
// ============================================================
const fmt2 = (v: number) =>
  v.toLocaleString("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** 부호 붙인 값 — 음수는 하이픈이 아니라 마이너스 기호. */
const signed2 = (v: number) =>
  `${v > 0 ? "+" : v < 0 ? "−" : ""}${Math.abs(v).toLocaleString("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

/**
 * 매크로 네 지표 — 목록에서는 한 줄씩이고, 상세는 `?t=`로 고른다.
 * 경로를 지표마다 나누지 않은 이유: `/market-analysis/macro` 하나가 이미 검색에 올라가 있고
 * (seo.json·sitemap), 경로를 늘리면 SEO 목록과 빌드 가드까지 같이 늘어난다.
 */
type MacroItem = { key: string; target: MacroTarget; name: string; title: string; unit: string; delayed?: boolean };
const MACRO_ITEMS: MacroItem[] = [
  { key: "usd-krw", target: "USD_KRW", name: "원달러", title: "원달러 환율", unit: "원" },
  // WTI만 CME 시세라 나스닥 선물과 같이 10분쯤 늦다
  { key: "wti", target: "WTI", name: "WTI", title: "WTI 유가", unit: "달러", delayed: true },
  { key: "vix", target: "VIX", name: "VIX", title: "VIX", unit: "지수" },
  { key: "us10y", target: "US10Y", name: "미국채 10년", title: "미국채 10년 금리", unit: "%" },
];

type ListRow = {
  key: string;
  name: string;
  to: string;
  active: boolean;
  delayed?: boolean;
  value: number | null;
  pct: number | null;
};
type ListGroup = { title: string; rows: ListRow[] };

/** 값이 아직 안 온 칸은 대시로 둔다(목값을 보여줬다 실값으로 바뀌면 오독한다). */
const quoteOf = (value?: number, pct?: number) =>
  value === undefined || pct === undefined ? { value: null, pct: null } : { value, pct };

function useIndexGroups(currentSlug: string, macroKey: string): ListGroup[] {
  const kospi = useKospiIndex();
  const kosdaq = useKosdaqIndex();
  const futures = useFuturesQuote("KOSPI");
  const kosdaqFutures = useFuturesQuote("KOSDAQ");
  const night = useNightFuturesQuote();
  const nasdaqIndex = useNasdaqIndexQuote();
  const nasdaqFutures = useNasdaqFuturesQuote();
  const macro = useMacroQuotes();

  const quotes: Record<string, { value: number | null; pct: number | null }> = {
    kospi: quoteOf(kospi.data?.currentValue, kospi.data?.changeRate),
    kospiF: quoteOf(futures.data?.futuresPrice, futures.data?.changeRate),
    kosdaq: quoteOf(kosdaq.data?.currentValue, kosdaq.data?.changeRate),
    kosdaqF: quoteOf(kosdaqFutures.data?.futuresPrice, kosdaqFutures.data?.changeRate),
    nightF: quoteOf(night.data?.price, night.data?.changeRate),
    nasdaq: quoteOf(nasdaqIndex.data?.price, nasdaqIndex.data?.changeRate),
    nasdaqF: quoteOf(nasdaqFutures.data?.price, nasdaqFutures.data?.changeRate),
  };
  const macroQuote: Record<MacroTarget, MacroQuote | null | undefined> = {
    USD_KRW: macro.data?.usdKrw,
    WTI: macro.data?.wti,
    VIX: macro.data?.vix,
    US10Y: macro.data?.us10y,
  };

  const indexRow = (id: string): ListRow => {
    const ix = INDICES.find((i) => i.id === id)!;
    return {
      key: ix.id,
      name: ix.name,
      to: `/market-analysis/${ix.slug}`,
      active: ix.slug === currentSlug,
      delayed: ix.delayed,
      ...quotes[ix.id],
    };
  };
  return [
    { title: "국내", rows: ["kospi", "kospiF", "kosdaq", "kosdaqF", "nightF"].map(indexRow) },
    { title: "해외", rows: ["nasdaq", "nasdaqF"].map(indexRow) },
    {
      title: "매크로",
      rows: MACRO_ITEMS.map((m) => {
        const q = macroQuote[m.target];
        return {
          key: m.key,
          name: m.name,
          to: `/market-analysis/macro?t=${m.key}`,
          active: currentSlug === "macro" && m.key === macroKey,
          delayed: m.delayed,
          ...quoteOf(q?.price, q?.changeRate),
        };
      }),
    },
  ];
}

function IndexList({ groups }: { groups: ListGroup[] }) {
  return (
    <nav aria-label="지수 목록">
      {groups.map((g) => (
        <div key={g.title}>
          <h2 className="px-3 pt-4 pb-1.5 text-[11px] font-semibold tracking-wide text-zinc-500">{g.title}</h2>
          <ul className="flex flex-col gap-0.5">
            {g.rows.map((r) => (
              <li key={r.key}>
                <Link
                  to={r.to}
                  aria-current={r.active ? "page" : undefined}
                  className={`flex items-center justify-between gap-3 rounded-xl px-3 py-2 transition-colors ${
                    r.active ? "bg-selected" : "hover:bg-zinc-850"
                  }`}
                >
                  <span
                    className={`flex min-w-0 items-center gap-1.5 text-[13px] ${
                      r.active ? "font-semibold text-zinc-100" : "font-medium text-zinc-300"
                    }`}
                  >
                    <span className="truncate">{r.name}</span>
                    {r.delayed && <DelayBadge />}
                  </span>
                  {r.value === null || r.pct === null ? (
                    <span className="num text-[13px] font-bold text-zinc-700">—</span>
                  ) : (
                    <span className="flex shrink-0 flex-col items-end">
                      <span className="num text-[13px] font-bold text-zinc-100">{fmt2(r.value)}</span>
                      <span className={`num text-[11px] font-semibold ${colorByPnL(r.pct)}`}>
                        {formatPct(r.pct / 100)}
                      </span>
                    </span>
                  )}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}

/** 모바일 — 세로 목록이 들어갈 폭이 없어 가로로 넘기는 칩. 고른 칩이 화면 밖이면 끌어온다. */
function IndexChips({ groups }: { groups: ListGroup[] }) {
  const activeRef = useRef<HTMLAnchorElement>(null);
  const rows = groups.flatMap((g) => g.rows);
  const activeKey = rows.find((r) => r.active)?.key;
  useEffect(() => {
    activeRef.current?.scrollIntoView({ inline: "center", block: "nearest" });
  }, [activeKey]);
  return (
    <nav aria-label="지수 목록" className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 sm:-mx-6 sm:px-6">
      {rows.map((r) => (
        <Link
          key={r.key}
          ref={r.active ? activeRef : undefined}
          to={r.to}
          aria-current={r.active ? "page" : undefined}
          className={`flex shrink-0 flex-col gap-0.5 rounded-xl px-3 py-2 ${
            r.active ? "bg-selected ring-1 ring-zinc-700" : "bg-zinc-900"
          }`}
        >
          <span className={`whitespace-nowrap text-xs ${r.active ? "font-semibold text-zinc-100" : "font-medium text-zinc-400"}`}>
            {r.name}
          </span>
          {r.pct === null ? (
            <span className="num text-xs font-bold text-zinc-700">—</span>
          ) : (
            <span className={`num whitespace-nowrap text-xs font-bold ${colorByPnL(r.pct)}`}>{formatPct(r.pct / 100)}</span>
          )}
        </Link>
      ))}
    </nav>
  );
}

// ============================================================
// 중앙: 선택 대상 상세
// ============================================================
/**
 * 수급 표 자리 관문 — 표를 아예 렌더하지 않는다.
 *
 * 컴포넌트를 안 그리면 그 안의 훅도 안 돌아, 미로그인에게 401이 폴링마다 쌓이지 않는다.
 * 막대는 가짜다. 시그널·지지저항과 달리 여기는 잘라 보낼 일부가 없어 서버는 그대로 401이다.
 */
function SupplyGate({ what }: { what: string }) {
  return (
    <div className="relative px-1">
      {/* 아래로 갈수록 지워진다 — 줄마다 농도를 주면 계단이 생겨 "이어진다"가 덜 읽힌다 */}
      <div
        aria-hidden
        className="select-none opacity-60"
        style={{
          maskImage: "linear-gradient(#000, transparent)",
          WebkitMaskImage: "linear-gradient(#000, transparent)",
        }}
      >
        {[0, 1, 2].map((i) => (
          <div key={i} className="flex items-center gap-3.5 py-3.5">
            <span className="h-3 rounded bg-zinc-700" style={{ width: i === 0 ? 84 : 62 }} />
            <span className="ml-auto h-3 w-14 rounded bg-zinc-700" />
            <span className="h-3 w-14 rounded bg-zinc-700" />
            <span className="h-3 w-14 rounded bg-zinc-700" />
            <span className="h-3 w-14 rounded bg-zinc-700" />
          </div>
        ))}
      </div>
      <div className="absolute inset-0 flex flex-col items-center justify-center gap-2.5">
        <p className="text-xs text-zinc-400">{what}은 로그인 후 확인할 수 있습니다</p>
        <GoogleLoginButton />
      </div>
    </div>
  );
}

function SubjectDetail({ ix, macro, authenticated }: { ix: IndexInfo; macro: MacroItem; authenticated: boolean }) {
  // 야간선물·나스닥·매크로는 수급 표가 없어 그대로 둔다
  const detail =
    ix.id === "kospiF" ? <FuturesIndexDetail index={ix} market="KOSPI" authenticated={authenticated} />
    : ix.id === "kosdaqF" ? <FuturesIndexDetail index={ix} market="KOSDAQ" authenticated={authenticated} />
    : ix.id === "nightF" ? <NightFuturesDetail index={ix} />
    : ix.id === "nasdaq" ? <NasdaqIndexDetail index={ix} />
    : ix.id === "nasdaqF" ? <NasdaqFuturesDetail index={ix} />
    : ix.id === "macro" ? <MacroDetail item={macro} />
    : ix.id === "kosdaq" ? <LiveIndexDetail market="KOSDAQ" name={ix.name} authenticated={authenticated} />
    : <LiveIndexDetail market="KOSPI" name={ix.name} authenticated={authenticated} />;
  return <div className="h-full lg:overflow-y-auto pr-1 pb-6">{detail}</div>;
}

const titleCls = "text-sm font-semibold text-zinc-400";
/** 차트 높이 — 큰 화면에서는 늘린다. 차트가 autoSize라 컨테이너 높이만 바꾸면 된다. */
const CHART_H = "h-[21.25rem] 2xl:h-[26rem]";

/**
 * 마지막 세션의 시가·고가·저가와 마지막 봉 — 헤더에 싣는다.
 *
 * 세션은 **55분 이상 끊긴 자리 뒤부터**로 자른다. 날짜로 자르면 자정을 넘는 미국장·야간선물이
 * 둘로 쪼개진다. 나스닥 선물·WTI는 CME 휴장(1시간)이 이 경계가 된다.
 * 원달러처럼 하루 넘게 끊김 없이 도는 시세는 "시가"가 없으므로 시고저를 싣지 않는다.
 */
function lastSession(items: MarketCandleItem[] | undefined) {
  if (!items?.length) return null;
  const at = (c: MarketCandleItem) => new Date(`${c.date}T${c.time.slice(0, 8)}+09:00`).getTime();
  const sorted = [...items].sort((x, y) => at(x) - at(y));
  let start = 0;
  for (let i = 1; i < sorted.length; i++) {
    if (at(sorted[i]) - at(sorted[i - 1]) >= 55 * 60_000) start = i;
  }
  const bars = sorted.slice(start);
  const last = bars[bars.length - 1];
  const spanHours = (at(last) - at(bars[0])) / 3_600_000;
  const stats =
    spanHours <= 24
      ? {
          open: bars[0].open,
          high: Math.max(...bars.map((c) => c.high)),
          low: Math.min(...bars.map((c) => c.low)),
        }
      : null;
  return { stats, last };
}

/** 마지막 봉 시각 → "09-23(수) 15:30 기준" */
function asOfLabel(c: MarketCandleItem): string {
  const [y, m, d] = c.date.split("-").map(Number);
  const dow = "일월화수목금토"[new Date(y, m - 1, d).getDay()];
  return `${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}(${dow}) ${c.time.slice(0, 5)} 기준`;
}

/** 지수 상세 헤더 — 이름·기준 시각 / 큰 가격·등락·등락률 배지 / 오른쪽에 시가·고가·저가·전일. */
function DetailHeader({
  name,
  price,
  pct,
  chg: chgProp,
  badge,
  candles,
}: {
  name: string;
  price: number;
  pct: number;
  chg?: number; // 전일 대비를 API가 정확히 줄 때. 없으면 등락률에서 역산
  badge?: ReactNode;
  candles?: MarketCandleItem[]; // 1분봉 — 기준 시각과 시고저를 여기서 뽑는다
}) {
  // 지수·선물은 소수 2자리(주가용 정수 반올림을 쓰면 1,210.50이 1,211로 뭉개짐)
  const chg = chgProp ?? price - price / (1 + pct / 100);
  const session = lastSession(candles);
  const stats: [string, number][] = [
    ...(session?.stats
      ? ([
          ["시가", session.stats.open],
          ["고가", session.stats.high],
          ["저가", session.stats.low],
        ] as [string, number][])
      : []),
    ["전일", price - chg],
  ];
  const pctBadge = pct > 0 ? "bg-red-500/10" : pct < 0 ? "bg-blue-500/10" : "bg-zinc-800";
  return (
    <header className="flex flex-wrap items-end justify-between gap-x-8 gap-y-3">
      <div className="flex min-w-0 flex-col gap-1.5">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
          <h2 className="font-semibold text-zinc-100">{name}</h2>
          {badge}
          {session && <span className="num text-zinc-500">· {asOfLabel(session.last)}</span>}
        </div>
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <span className="num text-3xl font-bold tracking-tight text-zinc-100 sm:text-4xl">{fmt2(price)}</span>
          <span className={`num text-base font-semibold ${colorByPnL(pct)}`}>{signed2(chg)}</span>
          <span className={`num rounded-md px-2 py-0.5 text-[13px] font-bold ${pctBadge} ${colorByPnL(pct)}`}>
            {formatPct(pct / 100)}
          </span>
        </div>
      </div>
      <dl className="flex gap-5 text-xs">
        {stats.map(([label, v]) => (
          <div key={label} className="flex flex-col items-end gap-1">
            <dt className="text-zinc-500">{label}</dt>
            <dd className="num font-semibold text-zinc-300">{fmt2(v)}</dd>
          </div>
        ))}
      </dl>
    </header>
  );
}

/** 차트 카드 — 제목 + 1분봉/일봉 토글 + 차트. */
function ChartCard({
  title,
  interval,
  onInterval,
  children,
}: {
  title: string;
  interval: ChartInterval;
  onInterval: (v: ChartInterval) => void;
  children: ReactNode;
}) {
  return (
    <section className="rounded-2xl bg-zinc-900 p-3 sm:p-4">
      <div className="mb-2 flex items-center justify-between gap-2 px-1">
        <h3 className={titleCls}>{title}</h3>
        <IntervalToggle value={interval} onChange={onInterval} />
      </div>
      {children}
    </section>
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
    <div className="flex rounded-xl bg-zinc-800 p-0.5 text-xs shrink-0">
      {INTERVAL_TABS.map((t) => (
        <button
          key={t.key}
          type="button"
          onClick={() => onChange(t.key)}
          className={`px-3 py-1.5 rounded-lg transition-colors ${
            value === t.key ? "bg-elevated text-zinc-100 font-medium" : "text-zinc-500 hover:text-zinc-300"
          }`}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

function LiveIndexDetail({
  market,
  name,
  authenticated,
}: {
  market: MarketType;
  name: string;
  authenticated: boolean;
}) {
  // 백엔드가 데이터 있는 가장 최근 거래일로 물러나고 실제 날짜를 함께 준다.
  // 여기서는 오늘을 그대로 보내면 된다 — 주말·공휴일 판정을 두 곳에 두지 않는다.
  const date = todayStr();
  const kospiQ = useKospiIndex();
  const kosdaqQ = useKosdaqIndex();
  const priceQ = market === "KOSPI" ? kospiQ : kosdaqQ;
  const minute = useMarketCandles(market, "1m");
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");

  if (priceQ.isLoading) return <Skeleton className="h-96 w-full" />;
  if (!priceQ.data) return <EmptyState message="지수를 불러오지 못했습니다" />;
  const value = priceQ.data.currentValue;
  const pct = priceQ.data.changeRate;

  return (
    <div className="flex flex-col gap-6">
      <DetailHeader name={name} price={value} pct={pct} candles={minute.data} />
      <ChartCard title="지수 차트" interval={chartInterval} onInterval={setChartInterval}>
        <LiveChart market={market} interval={chartInterval} />
      </ChartCard>
      {authenticated ? (
        <SpotSupply market={market} date={date} />
      ) : (
        <SupplyGateSection what="시간대별 수급과 최근 5일 수급" />
      )}
    </div>
  );
}

/** 지수선물 상세 — 종가베팅용. 헤더(선물가·베이시스·만기) + 베이시스 패널 + 차트. KIS 근월물 실시세. */
function FuturesIndexDetail({
  index,
  market,
  authenticated,
}: {
  index: IndexInfo;
  market: MarketType;
  authenticated: boolean;
}) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  const { data, isLoading } = useFuturesQuote(market);
  const minute = useFuturesCandles(market, "1m");
  const spotName = market === "KOSPI" ? "KOSPI200" : "KOSDAQ150";

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="선물 시세를 불러오지 못했습니다" />;

  const futValue = data.futuresPrice;
  const futPct = data.changeRate;
  const basis = data.basis; // 시장 베이시스 = 선물 − 현물
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
    <div className="flex flex-col gap-6">
      <DetailHeader name={index.name} price={futValue} pct={futPct} candles={minute.data} />

      <div className="px-1">
        <div className="flex items-baseline justify-between mb-3">
          <span className={titleCls}>베이시스</span>
          <span className="text-xs text-zinc-600">선물 − 현물({spotName})</span>
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

      <ChartCard title="선물 차트" interval={chartInterval} onInterval={setChartInterval}>
        <FuturesChart market={market} interval={chartInterval} />
      </ChartCard>

      {authenticated ? (
        <FuturesSupply market={market} date={todayStr()} />
      ) : (
        <SupplyGateSection what="선물 시간대별 수급과 일별 수급" />
      )}
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
  const minute = useNightFuturesCandles("1m");

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="야간선물 시세를 불러오지 못했습니다" />;

  const up = data.gap >= 0;
  const tone = up ? "text-red-400" : "text-blue-400";
  const signed2 = (v: number) => `${v >= 0 ? "+" : "−"}${Math.abs(v).toFixed(2)}`;

  return (
    <div className="flex flex-col gap-6">
      <DetailHeader
        name={index.name}
        price={data.price}
        pct={data.changeRate}
        chg={data.gap}
        candles={minute.data}
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

      <ChartCard title="야간선물 차트" interval={chartInterval} onInterval={setChartInterval}>
        <NightFuturesChart interval={chartInterval} />
      </ChartCard>
    </div>
  );
}

/**
 * 나스닥 종합지수(^IXIC) 상세 — 야후 파이낸스.
 * 현물이라 미 정규장(23:30~06:00 KST)에만 움직인다. 우리 장중엔 직전 마감가에 멈춰 있다.
 */
function NasdaqIndexDetail({ index }: { index: IndexInfo }) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  const { data, isLoading } = useNasdaqIndexQuote();
  const minute = useNasdaqIndexCandles("1m");

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="나스닥 지수를 불러오지 못했습니다" />;

  return (
    <div className="flex flex-col gap-6">
      <DetailHeader
        name={index.name}
        price={data.price}
        pct={data.changeRate}
        chg={data.priceChange}
        candles={minute.data}
      />
      <ChartCard title="나스닥 차트" interval={chartInterval} onInterval={setChartInterval}>
        <NasdaqIndexChart interval={chartInterval} />
      </ChartCard>
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
  if (isLoading) return <Skeleton className={`${CHART_H} w-full`} />;
  if (!items.length) return <EmptyState message="캔들 데이터가 없습니다" />;
  return (
    <CandleChart
      key={`nasdaq-${interval}`}
      series={series}
      timeVisible={interval === "1m"}
      priceDecimals={2}
      className={`w-full ${CHART_H}`}
    />
  );
}

/**
 * 나스닥 선물 상세 — 나스닥100 근월물(CME NQ).
 *
 * **현물과 나눠 두는 이유는 도는 시간이 다르기 때문이다.** 나스닥 현물은 미 정규장에만 움직여
 * 우리 장중엔 직전 마감가에 멈춰 있지만, 선물은 거의 하루 종일 돌아 장중 미국 심리를 읽어준다.
 * 야후 무료 시세라 10분쯤 지연된다 — 그 사실을 배지로 함께 적는다.
 */
function NasdaqFuturesDetail({ index }: { index: IndexInfo }) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");
  const { data, isLoading } = useNasdaqFuturesQuote();
  const minute = useNasdaqFuturesCandles("1m");

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="나스닥 선물 시세를 불러오지 못했습니다" />;

  return (
    <div className="flex flex-col gap-6">
      <DetailHeader
        name={index.name}
        price={data.price}
        pct={data.changeRate}
        chg={data.priceChange}
        badge={<DelayBadge />}
        candles={minute.data}
      />
      <ChartCard title="나스닥 선물 차트" interval={chartInterval} onInterval={setChartInterval}>
        <NasdaqFuturesChart interval={chartInterval} />
      </ChartCard>
    </div>
  );
}

/** 나스닥 선물 1분봉(최근 1일)/일봉(6개월) — 야후 캔들. */
function NasdaqFuturesChart({ interval }: { interval: ChartInterval }) {
  const { data, isLoading } = useNasdaqFuturesCandles(interval);
  const items = data ?? [];
  const series = useMemo(
    () => (interval === "1d" ? marketDailySeries(items) : marketMinuteSeries(items)),
    [items, interval],
  );
  if (isLoading) return <Skeleton className={`${CHART_H} w-full`} />;
  if (!items.length) return <EmptyState message="캔들 데이터가 없습니다" />;
  return (
    <CandleChart
      key={`nasdaq-futures-${interval}`}
      series={series}
      timeVisible={interval === "1m"}
      priceDecimals={2}
      className={`w-full ${CHART_H}`}
    />
  );
}

/**
 * 매크로 상세 — 목록에서 고른 지표 하나(`?t=`).
 * 환율은 24시간 돌지만 WTI는 CME 정산 휴식(06:00~07:00 KST)에, VIX는 미 정규장 밖에서 값이 멈춘다.
 * 미국채 10년(`^TNX`)은 미 채권시장 시간(KST 21:20~04:00, 2026-09 실측)에만 움직인다.
 */
function MacroDetail({ item }: { item: MacroItem }) {
  const { data, isLoading } = useMacroQuotes();
  const minute = useMacroCandles(item.target, "1m");
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  const quote =
    item.target === "USD_KRW" ? data?.usdKrw
    : item.target === "WTI" ? data?.wti
    : item.target === "VIX" ? data?.vix
    : data?.us10y;
  if (!quote) return <EmptyState message={`${item.title} 시세를 불러오지 못했습니다`} />;

  return (
    <div className="flex flex-col gap-6">
      <DetailHeader
        name={item.title}
        price={quote.price}
        pct={quote.changeRate}
        chg={quote.priceChange}
        badge={item.delayed ? <DelayBadge /> : undefined}
        candles={minute.data}
      />
      <ChartCard title={`${item.title} 차트 · ${item.unit}`} interval={chartInterval} onInterval={setChartInterval}>
        <MacroChart target={item.target} interval={chartInterval} />
      </ChartCard>
    </div>
  );
}

/** 매크로 1분봉(최근 2일)/일봉(6개월) — 야후 캔들. */
function MacroChart({ target, interval }: { target: MacroTarget; interval: ChartInterval }) {
  const { data, isLoading } = useMacroCandles(target, interval);
  const items = data ?? [];
  const series = useMemo(
    () => (interval === "1d" ? marketDailySeries(items) : marketMinuteSeries(items)),
    [items, interval],
  );
  if (isLoading) return <Skeleton className={`${CHART_H} w-full`} />;
  if (!items.length) return <EmptyState message="캔들 데이터가 없습니다" />;
  return (
    <CandleChart
      key={`macro-${target}-${interval}`}
      series={series}
      timeVisible={interval === "1m"}
      priceDecimals={2}
      className={`w-full ${CHART_H}`}
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
  if (isLoading) return <Skeleton className={`${CHART_H} w-full`} />;
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
      className={`w-full ${CHART_H}`}
    />
  );
}

/** 지수선물 1분봉/일봉 — KIS 근월물 캔들(OHLCV). */
function FuturesChart({ market, interval }: { market: MarketType; interval: ChartInterval }) {
  const { data, isLoading } = useFuturesCandles(market, interval);
  const items = data ?? [];
  const series = useMemo(
    () => (interval === "1d" ? marketDailySeries(items) : marketMinuteSeries(items)),
    [items, interval],
  );
  return isLoading ? (
    <Skeleton className={`${CHART_H} w-full`} />
  ) : items.length === 0 ? (
    <div className={`${CHART_H} flex items-center justify-center`}>
      <EmptyState message={interval === "1m" ? "장중에 1분봉이 표시됩니다" : "일봉 데이터가 없습니다"} />
    </div>
  ) : (
    <CandleChart
      key={`futures-${market}-${interval}`}
      series={series}
      timeVisible={interval === "1m"}
      priceDecimals={2}
      className={`w-full ${CHART_H}`}
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
        <Skeleton className={`${CHART_H} w-full`} />
      ) : items.length === 0 ? (
        <div className={`${CHART_H} flex items-center justify-center`}>
          <EmptyState message={interval === "1m" ? "장중에 1분봉이 표시됩니다" : "일봉 데이터가 없습니다"} />
        </div>
      ) : (
        <CandleChart
          key={`${market}-${interval}`}
          series={series}
          timeVisible={interval === "1m"}
          priceDecimals={2}
          className={`w-full ${CHART_H}`}
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


/**
 * 순매수 숫자 + 직전 스냅샷 대비 변화량.
 *
 * **변화량은 서버가 계산해 내려준다.** 예전에는 각 칸이 `localStorage`에 마지막 본 값을 들고
 * 스스로 뺐는데, 그러면 기준이 브라우저마다 달랐다 — 처음 온 사람은 한 주기를 기다려야 했고,
 * 자리를 비웠다 돌아오면 몇 시간치가 1분치인 척 깜빡였다.
 *
 * 값이 바뀔 때만 깜빡인다. 마지막 변화량은 장이 끝난 뒤에도 흐리게 남는다 —
 * 서버가 그날 마지막 두 스냅샷의 차이를 계속 실어 주기 때문이다.
 */
function FlowNum({ eok, delta }: { eok: number; delta?: number }) {
  // 같은 값이 다시 와도 재생하지 않게, 델타가 바뀔 때만 seq를 올린다
  const seqRef = useRef(0);
  const prevRef = useRef<number | undefined>(undefined);
  if (delta !== prevRef.current) {
    prevRef.current = delta;
    seqRef.current += 1;
  }
  return (
    <span className="inline-flex items-baseline justify-end gap-1.5">
      <NetNum eok={eok} />
      {delta != null && delta !== 0 && <DeltaFlash key={seqRef.current} delta={delta} />}
    </span>
  );
}

/** 변화량 배지 — key(seq)로 재마운트될 때마다 flow-delta 애니메이션이 다시 재생된다. */
function DeltaFlash({ delta }: { delta: number }) {
  const tone = delta > 0 ? "text-red-400" : "text-blue-400";
  return (
    <span className={`flow-delta num text-[11px] ${tone}`}>
      {delta > 0 ? "+" : "−"}
      {Math.abs(delta).toLocaleString("ko-KR")}
    </span>
  );
}

// ============================================================
// 투자자별 순매수 — 시간대별 / 최근 5일 탭. 현물(억원)·선물(계약) 공용.
// ============================================================
/** 순매수 한 묶음 — 현물·선물의 모양이 달라 화면용으로 한 번 맞춘다. */
type Nets = {
  individual: number;
  foreign: number;
  institution: number;
  otherCorp: number;
  breakdown: Record<string, number>;
};
type FlowCol = { key: string; label: string };
type FlowRow = { key: string; label: ReactNode; nets: Nets | null; delta?: Nets | null };

/** 조회 기준일 라벨. 주말엔 직전 평일을 보게 되므로 언제 것인지 밝힌다. */
function dayLabel(date: string): string {
  if (date === todayStr()) return "오늘";
  return shortDay(date);
}

/** "2026-09-23" → "09-23(수)" */
function shortDay(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  const dow = "일월화수목금토"[new Date(y, m - 1, d).getDay()];
  return `${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}(${dow})`;
}

/** 행 합계 — 집계 전(null) 행은 건너뛴다. 전부 비면 null. */
function sumNets(rows: FlowRow[], cols: FlowCol[]): Nets | null {
  const got = rows.map((r) => r.nets).filter((n): n is Nets => n !== null);
  if (!got.length) return null;
  const add = (pick: (n: Nets) => number) => got.reduce((acc, n) => acc + pick(n), 0);
  return {
    individual: add((n) => n.individual),
    foreign: add((n) => n.foreign),
    institution: add((n) => n.institution),
    otherCorp: add((n) => n.otherCorp),
    breakdown: Object.fromEntries(cols.map((c) => [c.key, add((n) => n.breakdown[c.key] ?? 0)])),
  };
}

const sessionLabel = (name: string, time: string) => (
  <>
    <div className="text-[13px] text-zinc-300">{name}</div>
    <div className="num text-[11px] text-zinc-500">{time}</div>
  </>
);
const dayRowLabel = (date: string) => <span className="num text-[13px] text-zinc-300">{shortDay(date)}</span>;

/**
 * 현물 수급 — 시간대별은 당일 누적 스냅샷 경계 diff(구간별 증분)라 스냅샷이 아직 없는 구간은 "집계 전".
 * 최근 5일은 키움 ka10051 — 일자별 1회씩 호출하므로 유량 제한(초당 5건) 안에서 5일로 제한.
 */
function SpotSupply({ market, date }: { market: MarketType; date: string }) {
  const sessions = useMarketInvestorSessions(market, date);
  const daily = useMarketInvestorDaily(market, 5);
  const spot = (r: MarketInvestorDay): Nets => ({
    individual: r.individualEok,
    foreign: r.foreignEok,
    institution: r.institutionEok,
    otherCorp: r.otherCorpEok,
    breakdown: r.breakdown as unknown as Record<string, number>,
  });
  return (
    <SupplyView
      unit="억원"
      cols={ORG_COLS}
      // 공휴일이면 백엔드가 직전 거래일로 물러난다 — 실제 조회된 날짜로 라벨을 붙인다
      sessionDate={sessions.data?.date ?? date}
      sessionRows={(sessions.data?.sessions ?? []).map((x) => ({
        key: x.name,
        label: sessionLabel(x.name, x.time),
        nets: x.nets && { ...x.nets, breakdown: x.nets.breakdown as unknown as Record<string, number> },
        delta: x.delta && { ...x.delta, breakdown: x.delta.breakdown as unknown as Record<string, number> },
      }))}
      dailyRows={(daily.data ?? []).map((r) => ({ key: r.date, label: dayRowLabel(r.date), nets: spot(r) }))}
      sessionLoading={sessions.isLoading}
      dailyLoading={daily.isLoading}
    />
  );
}

/**
 * 선물 수급(계약) — 시간대별은 현물과 같은 경계 diff다.
 * 최근 5일은 거래일별 마지막 스냅샷(= 그날의 당일 누적). 선물엔 일별 조회 API가 없어
 * 폴러가 쌓은 스냅샷으로만 만든다. 적재 시작 전 과거는 소급되지 않는다.
 */
function FuturesSupply({ market, date }: { market: MarketType; date: string }) {
  const sessions = useFuturesInvestorSessions(market, date);
  const daily = useFuturesInvestorDaily(market, 5);
  const fut = (n: FuturesNets): Nets => ({ ...n, breakdown: n.breakdown as unknown as Record<string, number> });
  return (
    <SupplyView
      unit="계약"
      sessionNote="정규장"
      cols={FUTURES_ORG_COLS}
      sessionDate={sessions.data?.date ?? date}
      sessionRows={(sessions.data?.sessions ?? []).map((x) => ({
        key: x.name,
        label: sessionLabel(x.name, x.time),
        nets: x.nets && fut(x.nets),
        delta: x.delta && fut(x.delta),
      }))}
      dailyRows={(daily.data ?? []).map((r) => ({ key: r.date, label: dayRowLabel(r.date), nets: fut(r.nets) }))}
      sessionLoading={sessions.isLoading}
      dailyLoading={daily.isLoading}
    />
  );
}

type SupplyTab = "session" | "daily";

function SupplyView({
  unit,
  sessionNote,
  cols,
  sessionDate,
  sessionRows,
  dailyRows,
  sessionLoading,
  dailyLoading,
}: {
  unit: string;
  sessionNote?: string;
  cols: FlowCol[];
  sessionDate: string;
  sessionRows: FlowRow[];
  dailyRows: FlowRow[];
  sessionLoading: boolean;
  dailyLoading: boolean;
}) {
  const [tab, setTab] = useState<SupplyTab>("session");
  const isSession = tab === "session";
  const rows = isSession ? sessionRows : dailyRows;
  const loading = isSession ? sessionLoading : dailyLoading;
  const total = sumNets(rows, cols);
  const sub = isSession
    ? `${dayLabel(sessionDate)}${sessionNote ? ` ${sessionNote}` : ""} · ${unit}`
    : `최근 ${dailyRows.length || 5}거래일 · ${unit}`;

  return (
    <SupplySection
      sub={sub}
      tabs={
        <div role="tablist" aria-label="수급 기간" className="flex shrink-0 rounded-xl bg-zinc-800 p-0.5 text-xs">
          {(
            [
              ["session", "시간대별"],
              ["daily", "최근 5일"],
            ] as [SupplyTab, string][]
          ).map(([key, label]) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={tab === key}
              onClick={() => setTab(key)}
              className={`rounded-lg px-3 py-1.5 transition-colors ${
                tab === key ? "bg-elevated font-medium text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
              }`}
            >
              {label}
            </button>
          ))}
        </div>
      }
    >
      {loading ? (
        <Skeleton className="h-64 w-full" />
      ) : rows.length === 0 ? (
        <EmptyState message={isSession ? "시간대별 수급 데이터가 없습니다" : "일별 수급 데이터가 없습니다"} />
      ) : (
        <>
          {/* 날짜·기간은 칸마다 적지 않는다 — 섹션 머리에 이미 있다 */}
          <FlowTiles nets={total} />
          <p className="-mb-1 text-[11px] text-zinc-500 xl:hidden">옆으로 밀어 기관 상세 보기 ›</p>
          <FlowTable
            firstLabel={isSession ? "시간대" : "일자"}
            cols={cols}
            rows={rows}
            totalLabel={isSession ? "합계" : `${rows.length}일 합계`}
            total={total}
          />
        </>
      )}
    </SupplySection>
  );
}

/** 수급 섹션 틀 — 제목 · 기준 · 오른쪽 탭. 관문(미로그인)도 같은 틀을 쓴다. */
function SupplySection({ sub, tabs, children }: { sub?: string; tabs?: ReactNode; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3.5">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 flex-wrap items-baseline gap-x-2.5 gap-y-0.5">
          <h3 className="text-[15px] font-bold text-zinc-100">투자자별 순매수</h3>
          {sub && <span className="num text-xs text-zinc-500">{sub}</span>}
        </div>
        {tabs}
      </div>
      {children}
    </section>
  );
}

function SupplyGateSection({ what }: { what: string }) {
  return (
    <SupplySection>
      <SupplyGate what={what} />
    </SupplySection>
  );
}

/** 개인·외국인·기관 요약 — 가운데 0에서 좌우로 뻗는 막대. 셋 중 가장 큰 값이 반 폭이다. */
function FlowTiles({ nets }: { nets: Nets | null }) {
  const items: [string, number | undefined][] = [
    ["개인", nets?.individual],
    ["외국인", nets?.foreign],
    ["기관", nets?.institution],
  ];
  const max = Math.max(1, ...items.map(([, v]) => Math.abs(v ?? 0)));
  return (
    <div className="grid grid-cols-3 gap-2 sm:gap-2.5">
      {items.map(([label, v]) => (
        <div key={label} className="flex flex-col gap-2.5 rounded-xl bg-zinc-900 px-3 py-2.5 sm:px-4 sm:py-3.5">
          <div className="flex flex-col gap-0.5 sm:flex-row sm:items-start sm:justify-between">
            <span className="text-[13px] text-zinc-300 sm:text-sm">
              {label}
            </span>
            <span className="text-[15px] font-bold sm:text-lg">
              {v === undefined ? <span className="text-zinc-700">—</span> : <NetNum eok={v} />}
            </span>
          </div>
          {v !== undefined && (
            <div className="relative hidden h-1.5 overflow-hidden rounded-full bg-zinc-850 sm:block">
              <div
                className={`absolute inset-y-0 rounded-full ${v > 0 ? "left-1/2 bg-red-600" : "right-1/2 bg-blue-600"}`}
                style={{ width: `${(Math.abs(v) / max) * 50}%` }}
              />
              <div className="absolute inset-y-[-2px] left-1/2 w-px bg-zinc-600" />
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

/**
 * 순매수 표 — 개인·외국인·기관계 + 기관 상세(펼친 채) + 기타법인, 맨 아래 합계.
 * 좁은 화면에서는 가로로 밀리고 첫 열(시간대·일자)은 붙어 있다.
 */
function FlowTable({
  firstLabel,
  cols,
  rows,
  totalLabel,
  total,
}: {
  firstLabel: string;
  cols: FlowCol[];
  rows: FlowRow[];
  totalLabel: string;
  total: Nets | null;
}) {
  const sticky = "sticky left-0 z-[1] bg-zinc-950";
  const org = (i: number) =>
    `bg-zinc-900 ${i === 0 ? "border-l border-zinc-800" : ""} ${i === cols.length - 1 ? "border-r border-zinc-800" : ""}`;
  const th = "px-2 py-1.5 text-right font-medium whitespace-nowrap";
  const cells = (n: Nets, delta: Nets | null | undefined, strong: boolean, line: string) => (
    <>
      {(["individual", "foreign", "institution"] as const).map((k) => (
        <td key={k} className={`px-2 py-2.5 text-right ${line} ${k === "institution" || strong ? "font-semibold" : ""}`}>
          <FlowNum eok={n[k]} delta={delta?.[k]} />
        </td>
      ))}
      {cols.map((c, i) => (
        <td key={c.key} className={`px-2 py-2.5 text-right ${line} ${org(i)} ${strong ? "font-semibold" : ""}`}>
          <FlowNum eok={n.breakdown[c.key] ?? 0} delta={delta?.breakdown[c.key]} />
        </td>
      ))}
      <td className={`px-2 py-2.5 text-right ${line} ${strong ? "font-semibold" : ""}`}>
        <FlowNum eok={n.otherCorp} delta={delta?.otherCorp} />
      </td>
    </>
  );
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[52rem] border-separate border-spacing-0 whitespace-nowrap text-xs">
        <thead className="text-zinc-500">
          <tr className="text-[11px]">
            <th className={sticky} />
            <th />
            <th />
            <th />
            <th
              colSpan={cols.length}
              className="rounded-t-lg border-x border-zinc-800 bg-zinc-900 pt-1 pb-1.5 text-center text-xs font-semibold text-zinc-400"
            >
              기관 상세
            </th>
            <th />
          </tr>
          <tr>
            <th className={`${sticky} py-1.5 pr-3 text-left font-medium`}>{firstLabel}</th>
            <th className={th}>개인</th>
            <th className={th}>외국인</th>
            <th className={th}>기관계</th>
            {cols.map((c, i) => (
              <th key={c.key} className={`${th} ${org(i)}`}>
                {c.label}
              </th>
            ))}
            <th className={th}>기타법인</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key} className="[&>td]:transition-colors hover:[&>td]:bg-zinc-850">
              <td className={`${sticky} border-t border-zinc-800 py-2 pr-3 text-left`}>{r.label}</td>
              {r.nets === null ? (
                <td colSpan={4 + cols.length} className="border-t border-zinc-800 px-2 py-2.5 text-right text-zinc-600">
                  집계 전
                </td>
              ) : (
                cells(r.nets, r.delta, false, "border-t border-zinc-800")
              )}
            </tr>
          ))}
          {total && (
            <tr>
              <td className={`${sticky} border-t border-zinc-700 py-2.5 pr-3 text-left text-[13px] font-bold text-zinc-100`}>
                {totalLabel}
              </td>
              {cells(total, null, true, "border-t border-zinc-700")}
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 지수는 URL이 정한다 — `/market-analysis/<slug>`.
 *
 * 지수·선물의 **시세와 차트는 로그인 없이 다 열린다**. 투자자 수급 표만 관문 뒤다 —
 * 가격은 어디서나 구할 수 있지만 투자자별 순매수는 이 화면이 가공해 주는 값이라서다.
 */
export default function ClosingBetPage() {
  const { slug } = useParams();
  const { data: me, isLoading } = useMe();
  if (isLoading) return null;

  const ix = INDICES.find((i) => i.slug === slug);
  if (!ix) return <Navigate to={`/market-analysis/${loadLastSlug()}`} replace />;

  return <ClosingBetPageInner ix={ix} authenticated={!!me?.authenticated} />;
}
