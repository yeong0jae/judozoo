import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  useKospiIndex,
  useLeaderTimeline,
  useKosdaqIndex,
  useLeadingStockLeaders,
  useMarketCalendarStatus,
  useNasdaqIndexQuote,
  useNightFuturesQuote,
  useOverseasLeaders,
  useTodayNets,
  useInsightReasons,
} from "../api/queries";
import { useMe } from "../api/auth";
import {
  formatClock,
  formatTradingDay,
  krTradingDay,
  usTradingDay,
  overseasIsMain,
  useMarketSessions,
} from "../lib/marketSession";
import { rememberMarket, type StockMarket } from "../lib/stockMarket";
import { foldRelay, leaderRelay, type RelayLeg } from "../lib/leaderTimeline";
import { formatKoreanMoney, formatPct } from "../lib/format";
import SessionStrip from "../components/layout/SessionStrip";
import ProfitText from "../components/common/ProfitText";
import NumWon from "../components/common/NumWon";
import NumUsd from "../components/common/NumUsd";
import Skeleton from "../components/common/Skeleton";
import GoogleLoginButton from "../components/common/GoogleLoginButton";
import { ReasonLine } from "../components/common/InsightReason";
import LeaderGuide from "../components/common/LeaderGuide";
import type { LimitUpItem, TodayNetItem } from "../types";

const LEADERS_COUNT = 5;

export default function HomePage() {
  const { kr, us, now } = useMarketSessions();
  const krCalendar = useMarketCalendarStatus("KR").data;
  const krHoliday = krCalendar?.isHoliday;
  const usHoliday = useMarketCalendarStatus("US").data?.isHoliday;

  const domesticLive = kr !== null && !krHoliday;
  const overseasLive = us !== null && !usHoliday;
  const domesticOpen = kr?.tone === "open" && !krHoliday;
  const overseasOpen = us?.tone === "open" && !usHoliday;
  const domesticFirst = !overseasIsMain(now, krHoliday);

  const clock = formatClock(now);
  const krDay = krTradingDay(now, !!krHoliday, krCalendar?.previousOpenDay);
  const usDay = usTradingDay(now, !!usHoliday);
  const domestic = (
    <DomesticLeaders
      live={domesticLive}
      first={domesticFirst}
      date={formatTradingDay(krDay)}
      tradingDay={isoDay(krDay)}
      clock={clock}
    />
  );
  const overseas = (
    <OverseasLeaders
      live={overseasLive}
      first={!domesticFirst}
      date={formatTradingDay(usDay)}
      tradingDay={isoDay(usDay)}
      clock={clock}
    />
  );

  return (
    <div className="flex flex-col gap-5 sm:gap-6">
      <div className="flex flex-col gap-2">
        <h1 className="text-xl font-extrabold text-zinc-100 sm:text-[22px]">{todayTitle(now)}</h1>
        <SessionStrip />
      </div>
      <IndexTiles domesticOpen={domesticOpen} overseasOpen={overseasOpen} domesticFirst={domesticFirst} />

      <div className="grid grid-cols-1 items-start gap-3 lg:grid-cols-2 lg:gap-4">
        {domesticFirst ? (
          <>
            {domestic}
            {overseas}
          </>
        ) : (
          <>
            {overseas}
            {domestic}
          </>
        )}
      </div>

      <TodayNets live={domesticOpen} clock={clock} today={isoDay(now)} />
    </div>
  );
}

function isoDay(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function todayTitle(now: Date): string {
  return `${now.getMonth() + 1}월 ${now.getDate()}일 ${"일월화수목금토"[now.getDay()]}요일`;
}

function IndexTiles({
  domesticOpen,
  overseasOpen,
  domesticFirst,
}: {
  domesticOpen: boolean;
  overseasOpen: boolean;
  domesticFirst: boolean;
}) {
  const kospi = useKospiIndex();
  const kosdaq = useKosdaqIndex();
  const night = useNightFuturesQuote();
  const nasdaq = useNasdaqIndexQuote();
  const tag = domesticOpen ? "장중" : "종가";

  const tiles = {
    kospi: (
      <Tile
        key="kospi"
        label="코스피"
        slug="kospi"
        tag={tag}
        value={kospi.data?.currentValue}
        rate={kospi.data?.changeRate}
      />
    ),
    kosdaq: (
      <Tile
        key="kosdaq"
        label="코스닥"
        slug="kosdaq"
        tag={tag}
        value={kosdaq.data?.currentValue}
        rate={kosdaq.data?.changeRate}
      />
    ),
    night: (
      <Tile
        key="night"
        label="코스피 야간 선물"
        slug="night-futures"
        value={night.data?.price}
        rate={night.data?.changeRate}
      />
    ),
    nasdaq: (
      <Tile
        key="nasdaq"
        label="나스닥"
        slug="nasdaq"
        tag={overseasOpen ? "장중" : "종가"}
        value={nasdaq.data?.price}
        rate={nasdaq.data?.changeRate}
      />
    ),
  };

  const order = domesticFirst
    ? [tiles.kospi, tiles.kosdaq, tiles.night, tiles.nasdaq]
    : [tiles.nasdaq, tiles.night, tiles.kospi, tiles.kosdaq];

  return (
    <div className="grid grid-cols-2 gap-2 lg:grid-cols-4 lg:gap-3">
      {order}
    </div>
  );
}

function Tile({
  label,
  slug,
  tag,
  value,
  rate,
}: {
  label: string;
  slug: string;
  tag?: string;
  value: number | undefined;
  rate: number | undefined;
}) {
  return (
    <Link
      to={`/market-analysis/${slug}`}
      className="flex min-w-0 flex-col gap-1.5 rounded-2xl bg-zinc-900 px-3.5 py-3 transition-colors hover:bg-zinc-850 sm:gap-2 sm:px-[18px] sm:py-4"
    >
      <div className="flex items-center gap-1.5 whitespace-nowrap text-[12.5px] text-zinc-300 sm:text-[13.5px]">
        {label}
        {tag && (
          <span
            className={
              tag === "장중"
                ? "rounded-full bg-blue-50 px-2 py-px text-[11px] font-medium text-blue-700"
                : "rounded border border-zinc-800 px-1 text-[11px] text-zinc-500"
            }
          >
            {tag}
          </span>
        )}
      </div>
      {value === undefined || rate === undefined ? (
        <Skeleton className="h-7 w-28" />
      ) : (
        <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-0.5">
          <span className="num text-[19px] font-bold tracking-tight text-zinc-100 sm:text-[22px]">
            {value.toLocaleString("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </span>
          <ProfitText value={rate} format={(v) => formatPct(v / 100)} className="num text-sm" />
        </div>
      )}
    </Link>
  );
}

type Item = {
  key: string;
  name: string;
  code: string;
  price: ReactNode;
  rate: number;
  value: number;
  valueLabel: string;
  reason?: ReactNode;
};

function compactUsd(v: number): string {
  if (v >= 1e9) return `$${(v / 1e9).toFixed(2)}B`;
  if (v >= 1e6) return `$${(v / 1e6).toFixed(1)}M`;
  return `$${Math.round(v).toLocaleString("en-US")}`;
}

const shortCode = (code: string) => (code.includes("_") ? code.slice(0, code.indexOf("_")) : code);

type LeadersProps = {
  live: boolean;
  first: boolean;
  date: string;
  tradingDay: string;
  clock: string;
};

function useRelay(market: "kr" | "us", tradingDay: string, live: boolean): RelayLeg[] {
  const { data } = useLeaderTimeline(market, tradingDay, live);
  return useMemo(() => (data ? leaderRelay(market, tradingDay, data) : []), [market, tradingDay, data]);
}

function DomesticLeaders({ live, first, date, tradingDay, clock }: LeadersProps) {
  const { data, isLoading } = useLeadingStockLeaders();
  const relay = useRelay("kr", tradingDay, live);
  const reasons = useInsightReasons("kr").data;
  const items: Item[] = (data?.leaders ?? []).map((s) => ({
    key: s.stockCode,
    name: s.stockName,
    code: shortCode(s.stockCode),
    price: <NumWon value={s.currentPrice} />,
    rate: s.priceChangeRate,
    value: s.accumulatedTradingValue,
    valueLabel: formatKoreanMoney(s.accumulatedTradingValue),
    reason: <ReasonLine item={reasons?.get(shortCode(s.stockCode))} className="mt-1.5" />,
  }));

  return (
    <LeaderCard
      title="국내 주도주"
      date={date}
      clock={clock}
      market="domestic"
      live={live}
      first={first}
      loading={isLoading}
      items={items}
      limitUps={data?.limitUps ?? []}
      relay={relay}
    />
  );
}

function OverseasLeaders({ live, first, date, tradingDay, clock }: LeadersProps) {
  const { data, isLoading } = useOverseasLeaders();
  const relay = useRelay("us", tradingDay, live);
  const reasons = useInsightReasons("us").data;
  const items: Item[] = (data ?? []).map((s) => ({
    key: `${s.exchange}:${s.symbol}`,
    name: s.name,
    code: s.symbol,
    price: <NumUsd value={s.price} prefix="" />,
    rate: s.rate,
    value: s.tradingValue,
    valueLabel: compactUsd(s.tradingValue),
    reason: <ReasonLine item={reasons?.get(s.symbol)} className="mt-1.5" />,
  }));

  return (
    <LeaderCard
      title="해외 주도주"
      date={date}
      clock={clock}
      market="overseas"
      live={live}
      first={first}
      loading={isLoading}
      items={items}
      relay={relay}
    />
  );
}

function CardHead({ title, sub, more }: { title: string; sub?: ReactNode; more?: string }) {
  return (
    <div className="flex items-center justify-between gap-2.5 px-3.5 pb-2 pt-3.5 sm:px-[18px] sm:pb-3 sm:pt-4">
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
        <h2 className="whitespace-nowrap text-[15px] font-bold text-zinc-100">{title}</h2>
        {sub}
      </div>
      {more && (
        <span className="flex shrink-0 items-center gap-0.5 whitespace-nowrap text-xs text-zinc-400">
          {more}
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="text-zinc-500">
            <path d="M9 6l6 6-6 6" />
          </svg>
        </span>
      )}
    </div>
  );
}

const cardCls = "flex min-w-0 flex-col overflow-hidden rounded-2xl bg-zinc-900 pb-1.5 transition-colors hover:bg-zinc-900/80";

const LEADER_COLS = "sm:grid-cols-[1rem_minmax(0,1fr)_7rem_6rem_4.5rem]";

function LeaderCard({
  title,
  date,
  clock,
  market,
  live,
  first,
  loading,
  items,
  limitUps = [],
  relay,
}: {
  title: string;
  date: string;
  clock: string;
  market: StockMarket;
  live: boolean;
  first: boolean;
  loading: boolean;
  items: Item[];
  limitUps?: LimitUpItem[];
  relay: RelayLeg[];
}) {
  const max = Math.max(1, ...items.map((s) => s.value));
  return (
    <div className={`relative ${cardCls} ${first ? "outline outline-1 outline-emerald-700/40" : "opacity-70"}`}>
      <Link
        to="/leading-stocks"
        onClick={() => rememberMarket(market)}
        aria-label={`${title} 전체 보기`}
        className="absolute inset-0 z-[1] rounded-2xl"
      />
      <CardHead
        title={title}
        more="전체 보기"
        sub={
          <>
            <LeaderGuide market={market} />
            <span className="num text-xs text-zinc-500">{date}</span>
            {live ? (
              <span className="rounded-full bg-blue-50 px-2 py-px text-[11px] font-medium text-blue-700">
                장중 <span className="num">{clock}</span>
              </span>
            ) : (
              <span className="text-[11px] text-zinc-500">마감 기준</span>
            )}
          </>
        }
      />

      {loading ? (
        <div className="space-y-2 px-4 pb-3">
          {Array.from({ length: LEADERS_COUNT }).map((_, i) => (
            <Skeleton key={i} className="h-5 w-full" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <p className="px-3.5 py-7 text-center text-xs text-zinc-500">현재 주도주가 없습니다</p>
      ) : (
        <>
          <div className={`hidden gap-x-3 px-[18px] pb-1.5 text-[11px] text-zinc-500 sm:grid ${LEADER_COLS}`}>
            <span />
            <span>종목</span>
            <span className="text-right">거래대금</span>
            <span className="text-right">현재가</span>
            <span className="text-right">등락률</span>
          </div>
          {items.map((s, i) => (
            <LeaderRow key={s.key} rank={i + 1} item={s} ratio={s.value / max} />
          ))}
        </>
      )}

      {limitUps.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5 border-t border-zinc-800 px-4 pb-1.5 pt-3">
          <span className="mr-0.5 text-[11.5px] font-medium text-zinc-400">
            상한가 <span className="num">{limitUps.length}</span>
          </span>
          {limitUps.map((s) => (
            <span key={s.stockCode} className="rounded-full bg-rose-50 px-2.5 py-0.5 text-[12.5px] text-rose-700">
              {s.stockName}
            </span>
          ))}
        </div>
      )}

      {relay.length > 0 && <LeaderRelay market={market} legs={relay} />}
    </div>
  );
}

function LeaderRelay({ market, legs }: { market: StockMarket; legs: RelayLeg[] }) {
  const box = useRef<HTMLSpanElement>(null);
  const probe = useRef<HTMLSpanElement>(null);
  const [compact, setCompact] = useState(false);
  useLayoutEffect(() => {
    const fit = () => setCompact(!!box.current && !!probe.current && probe.current.offsetWidth > box.current.clientWidth);
    fit();
    const ro = new ResizeObserver(fit);
    if (box.current) ro.observe(box.current);
    return () => ro.disconnect();
  }, [legs]);

  return (
    <Link
      to={`/leader-timeline?market=${market === "overseas" ? "us" : "kr"}`}
      className="relative z-[2] mx-2.5 mt-1 flex items-center gap-2.5 rounded-b-xl border-t border-zinc-800 px-2 py-2.5 hover:bg-zinc-850 sm:mx-3"
    >
      <span className="shrink-0 whitespace-nowrap text-[11.5px] font-medium text-zinc-400">
        <span className="hidden sm:inline">오늘 </span>1위
      </span>
      <span ref={box} className="relative flex min-w-0 flex-1 overflow-hidden sm:hidden">
        <span ref={probe} aria-hidden className="invisible absolute left-0 top-0 w-max">
          <RelayLine legs={legs} max={2} />
        </span>
        <RelayLine legs={legs} max={compact ? 1 : 2} />
      </span>
      <span className="hidden min-w-0 flex-1 sm:flex">
        <RelayLine legs={legs} max={3} wrap />
      </span>
      <span className="flex shrink-0 items-center gap-0.5 whitespace-nowrap text-xs text-zinc-400">
        <span className="hidden sm:inline">타임라인</span>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="text-zinc-500">
          <path d="M9 6l6 6-6 6" />
        </svg>
      </span>
    </Link>
  );
}

function RelayLine({ legs, max, wrap = false }: { legs: RelayLeg[]; max: number; wrap?: boolean }) {
  const { head, folded, tail } = foldRelay(legs, max);
  const parts: ReactNode[] = [
    ...head.map((l) => <RelayItem key={`h${l.at}`} leg={l} />),
    ...(folded ? [<span key="fold" className="shrink-0 whitespace-nowrap rounded-full bg-zinc-850 px-1.5 py-px text-[11px] text-zinc-400">외 {folded}</span>] : []),
    ...tail.map((l) => <RelayItem key={`t${l.at}`} leg={l} />),
  ];
  return (
    <span className={`flex min-w-0 items-center gap-x-1.5 gap-y-1 ${wrap ? "flex-wrap" : "flex-nowrap"}`}>
      {parts.map((p, i) => (
        <span key={i} className="flex min-w-0 items-center gap-1.5">
          {i > 0 && <span className="text-zinc-500">→</span>}
          {p}
        </span>
      ))}
    </span>
  );
}

function RelayItem({ leg }: { leg: RelayLeg }) {
  return (
    <span className="flex min-w-0 items-baseline gap-1 whitespace-nowrap text-[12.5px] text-zinc-100">
      <span className="truncate">{leg.name}</span>
      <span className="num shrink-0 text-[11px] text-zinc-500">{leg.at}</span>
    </span>
  );
}

function LeaderRow({ rank, item, ratio }: { rank: number; item: Item; ratio: number }) {
  return (
    <div className={`flex items-center gap-2.5 border-t border-zinc-800 px-3.5 py-2.5 sm:grid sm:gap-x-3 sm:px-[18px] ${LEADER_COLS}`}>
      <span className="num w-3 shrink-0 text-right text-[11px] text-zinc-500 sm:w-auto sm:text-xs">{rank}</span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="truncate text-[13.5px] text-zinc-100">{item.name}</span>
        <span className="num text-[11px] text-zinc-500">
          {item.code}
          <span className="sm:hidden"> · {item.valueLabel}</span>
        </span>
        {item.reason}
      </span>
      <span className="hidden flex-col items-end gap-1.5 sm:flex">
        <span className="num text-xs text-zinc-400">{item.valueLabel}</span>
        <span className="relative h-0.5 w-full overflow-hidden rounded-full bg-zinc-850">
          <span className="absolute inset-y-0 right-0 rounded-full bg-zinc-500" style={{ width: `${ratio * 100}%` }} />
        </span>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-0.5 sm:contents">
        <span className="num text-right text-[13.5px] text-zinc-100">{item.price}</span>
        <span className="num text-right text-xs">
          <ProfitText value={item.rate} format={(v) => formatPct(v / 100)} />
        </span>
      </span>
    </div>
  );
}

const INVESTORS = [
  { key: "individual", label: "개인" },
  { key: "foreign", label: "외인" },
  { key: "institution", label: "기관" },
  { key: "otherCorp", label: "기타법인" },
] as const;

const MARKET_NAME: Record<string, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };

function netLabel(item: TodayNetItem): { name: string; unit: string; slug: string } {
  const name = MARKET_NAME[item.market] ?? item.market;
  const slug = item.market.toLowerCase();
  return item.futures
    ? { name: `${name} 선물`, unit: "계약", slug: `${slug}-futures` }
    : { name, unit: "억원", slug };
}

function TodayNets({ live, clock, today }: { live: boolean; clock: string; today: string }) {
  const { data: me } = useMe();
  const authenticated = !!me?.authenticated;
  const { data, isLoading } = useTodayNets(authenticated);
  const basis = data?.find((item) => item.nets && item.tradeDate)?.tradeDate;
  const pastBasis = basis && basis !== today ? basis : null;

  if (!authenticated) {
    return (
      <div className="flex flex-col items-center gap-2.5 rounded-2xl border border-dashed border-zinc-800 px-4 py-5 text-center">
        <span className="text-[13.5px] font-semibold text-zinc-100">오늘의 수급은 로그인 후 확인할 수 있습니다.</span>
        <span className="text-xs text-zinc-500">코스피·코스닥 현물과 선물의 투자자별 순매수</span>
        <GoogleLoginButton />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <h2 className="text-[15px] font-bold text-zinc-100">오늘의 수급</h2>
        {pastBasis && <span className="num text-[11px] text-zinc-500">{shortDay(pastBasis)} 기준</span>}
        {live && (
          <span className="rounded-full bg-blue-50 px-2 py-px text-[11px] font-medium text-blue-700">
            장중 <span className="num">{clock}</span>
          </span>
        )}
        <span className="ml-auto text-[11.5px] text-zinc-500">
          순매수 <span className="text-red-400">빨강</span> · 순매도 <span className="text-blue-400">파랑</span>
        </span>
      </div>

      {isLoading ? (
        <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 lg:grid-cols-4 lg:gap-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-[9.5rem] w-full rounded-2xl" />
          ))}
        </div>
      ) : !data || data.length === 0 ? (
        <p className="rounded-2xl bg-zinc-900 px-4 py-7 text-center text-xs text-zinc-500">아직 오늘 수급이 없습니다</p>
      ) : (
        <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 lg:grid-cols-4 lg:gap-3">
          {data.map((item) => (
            <NetCard key={`${item.market}-${item.futures}`} item={item} />
          ))}
        </div>
      )}
    </div>
  );
}

function shortDay(iso: string): string {
  const [, m, d] = iso.split("-").map(Number);
  return `${m}/${d}`;
}

function NetCard({ item }: { item: TodayNetItem }) {
  const { name, unit, slug } = netLabel(item);
  const nets = item.nets;
  const top = nets ? Math.max(...INVESTORS.map(({ key }) => Math.abs(nets[key]))) || 1 : 1;

  return (
    <Link
      to={`/market-analysis/${slug}`}
      className="block min-w-0 rounded-2xl bg-zinc-900 px-4 py-3.5 transition-colors hover:bg-zinc-850 sm:px-[18px] sm:py-4"
    >
      <div className="flex items-baseline gap-1.5">
        <span className="text-[13.5px] font-semibold text-zinc-200">{name}</span>
        <span className="num ml-auto text-xs text-zinc-400">
          {item.indexValue.toLocaleString("ko-KR")}
        </span>
        <ProfitText
          value={item.changeRate}
          format={(v) => formatPct(v / 100)}
          className="num text-xs font-medium"
        />
      </div>

      <div className="mt-2.5 space-y-2 border-t border-zinc-800 pt-2.5">
        {INVESTORS.map(({ key, label }) => (
          <NetRow key={key} label={label} value={nets ? nets[key] : null} top={top} />
        ))}
      </div>

      <div className="mt-2.5 text-right text-[11px] text-zinc-500">단위 {unit}</div>
    </Link>
  );
}

function NetRow({ label, value, top }: { label: string; value: number | null; top: number }) {
  const width = value === null || value === 0 ? 0 : Math.max(1, (Math.abs(value) / top) * 50);
  const tone =
    value === null ? "text-zinc-600"
    : value > 0 ? "text-red-400"
    : value < 0 ? "text-blue-400"
    : "text-zinc-600";
  const sign = value !== null && value > 0 ? "+" : value !== null && value < 0 ? "−" : "";

  return (
    <div className="flex items-center gap-2">
      <span className="w-[3.4rem] shrink-0 text-[11.5px] text-zinc-400">{label}</span>
      <div className="relative h-2 min-w-0 flex-1">
        <div className="absolute left-1/2 top-[-1px] h-2.5 w-px bg-zinc-700" />
        <div
          className={`absolute top-0 h-2 ${
            value !== null && value < 0
              ? "right-1/2 rounded-l-sm bg-blue-400"
              : "left-1/2 rounded-r-sm bg-red-400"
          }`}
          style={{ width: `${width}%` }}
        />
      </div>
      <span className={`num w-[3.9rem] shrink-0 text-right text-xs font-medium ${tone}`}>
        {value === null ? "—" : `${sign}${Math.abs(value).toLocaleString("ko-KR")}`}
      </span>
    </div>
  );
}
