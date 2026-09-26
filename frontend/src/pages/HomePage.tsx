import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "../api/client";
import {
  useBreakoutRadar,
  useKospiIndex,
  useKosdaqIndex,
  useLeadingStockLeaders,
  useMarketCalendarStatus,
  useNasdaqIndexQuote,
  useNightFuturesQuote,
  useOverseasLeaders,
  useTodayNets,
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
import { formatKoreanMoney, formatPct, formatPrice } from "../lib/format";
import SessionStrip from "../components/layout/SessionStrip";
import ProfitText from "../components/common/ProfitText";
import NumWon from "../components/common/NumWon";
import NumUsd from "../components/common/NumUsd";
import Skeleton from "../components/common/Skeleton";
import GoogleLoginButton from "../components/common/GoogleLoginButton";
import type { LimitUpItem, SignalEventsResponse, TodayNetItem } from "../types";
import { EVENT_META, clockOf, detailOf } from "../components/common/signalParts";

/** 카드 한 장에 올리는 주도주 줄 수. 서버가 이미 그만큼만 내려준다 — 뼈대 높이에 쓴다. */
const LEADERS_COUNT = 5;

/**
 * 첫 화면 — 지금 시장이 어떤지만 보여준다.
 *
 * 로그인 없이 볼 수 있는 것으로만 채운다(지수·야간선물·양쪽 주도주 목록).
 * 장이 도는 쪽이 왼쪽 주인공 자리에 오고, 쉬는 쪽은 흐리게 내려간다 —
 * 밤에 보는 국내 목록은 살아 있는 숫자가 아니라서다.
 */
export default function HomePage() {
  const { kr, us, now } = useMarketSessions();
  const krCalendar = useMarketCalendarStatus("KR").data;
  const krHoliday = krCalendar?.isHoliday;
  const usHoliday = useMarketCalendarStatus("US").data?.isHoliday;

  // 세션 중인가 — 국내는 프리·애프터마켓까지(08:00~20:00), 해외는 프리마켓~정규장이다
  // (해외 세션 정의에 애프터마켓이 없다). 주도주 카드의 "장중" 칩이 이 값을 쓴다.
  // 어느 쪽을 앞에 둘지는 이 값이 아니라 아래 `overseasIsMain`이 정한다.
  const domesticLive = kr !== null && !krHoliday;
  const overseasLive = us !== null && !usHoliday;
  // 지수 타일만 정규장으로 좁힌다. 지수는 정규장에만 체결돼서, 프리·애프터마켓에 "장중"이라
  // 붙이면 멈춰 있는 숫자가 살아 있는 값으로 읽힌다.
  const domesticOpen = kr?.tone === "open" && !krHoliday;
  const overseasOpen = us?.tone === "open" && !usHoliday;
  // 주인공 자리는 시각이 정한다 — 국내가 여는 평일 08:00~19:59만 국내고 나머지는 해외다.
  // 지수 타일도 같은 규칙을 쓴다(`overseasIsMain`).
  const domesticFirst = !overseasIsMain(now, krHoliday);

  // 두 쪽 날짜가 다를 수 있다 — 해외는 미국 현지 거래일이라 한국 오전에는 하루 뒤처진다
  const clock = formatClock(now);
  const domestic = (
    <DomesticLeaders
      live={domesticLive}
      date={formatTradingDay(krTradingDay(now, !!krHoliday, krCalendar?.previousOpenDay))}
      clock={clock}
    />
  );
  const overseas = (
    <OverseasLeaders
      live={overseasLive}
      date={formatTradingDay(usTradingDay(now, !!usHoliday))}
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

      {/* 목록 화면들로 들어가는 문 — 둘 다 비로그인에게도 열린 미리보기(최신 3건)를 그대로 쓴다 */}
      <div className="grid grid-cols-1 items-start gap-3 lg:grid-cols-2 lg:gap-4">
        <RecentSignals />
        <NearBreakout />
      </div>

      <TodayNets live={domesticOpen} clock={clock} />
    </div>
  );
}

/** "9월 26일 토요일" */
function todayTitle(now: Date): string {
  return `${now.getMonth() + 1}월 ${now.getDate()}일 ${"일월화수목금토"[now.getDay()]}요일`;
}

// ============================================================
// 지수
// ============================================================

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

  // 슬러그는 lib/indices.ts가 정의한 것과 같아야 한다
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
        sub={
          night.data
            ? // 선물은 소수 둘째 자리까지 호가된다 — 주가용 정수 반올림을 쓰면 1,127.75가 1,128로 뭉개진다
              `전일 종가 ${night.data.dayClose.toLocaleString("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} · 갭 ${night.data.gap.toFixed(2)}`
            : undefined
        }
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

  // 주도주 카드와 같은 규칙이다 — 국내가 멈춰 있는 시간대에는 살아 있는 숫자를 앞에 둔다.
  // 낮에는 국내 둘이 주인공이고 나스닥은 밤사이 분위기를 재는 참고값이라 맨 뒤다.
  const order = domesticFirst
    ? [tiles.kospi, tiles.kosdaq, tiles.night, tiles.nasdaq]
    : [tiles.nasdaq, tiles.night, tiles.kospi, tiles.kosdaq];

  return (
    <div className="grid grid-cols-2 gap-2 lg:grid-cols-4 lg:gap-3">
      {order}
    </div>
  );
}

/** 누르면 그 지수의 지수·수급 화면으로. 로그인이 필요한 지수는 거기서 안내가 뜬다. */
function Tile({
  label,
  slug,
  tag,
  value,
  rate,
  sub,
}: {
  label: string;
  slug: string;
  tag?: string;
  value: number | undefined;
  rate: number | undefined;
  sub?: string;
}) {
  return (
    <Link
      to={`/market-analysis/${slug}`}
      className="flex min-w-0 flex-col gap-1.5 rounded-2xl bg-zinc-900 px-3.5 py-3 transition-colors hover:bg-zinc-850 sm:gap-2 sm:px-[18px] sm:py-4"
    >
      <div className="flex items-center gap-1.5 whitespace-nowrap text-[12.5px] text-zinc-300 sm:text-[13px]">
        {label}
        {/* 장중 칩은 주도주 카드의 "장중 14:07"과 같은 색이다 — 한 화면에서 같은 뜻이
            다른 색으로 보이면, 둘이 다른 상태를 가리키는 줄 읽는다 */}
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
        // 값은 흰색, 색은 등락률에만 — 지수·수급 목록과 같은 규칙이다
        <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-0.5">
          <span className="num text-[19px] font-bold tracking-tight text-zinc-100 sm:text-2xl">
            {value.toLocaleString("ko-KR", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </span>
          <ProfitText value={rate} format={(v) => formatPct(v / 100)} className="num text-[13px]" />
        </div>
      )}
      {sub && <div className="num text-[11px] text-zinc-500">{sub}</div>}
    </Link>
  );
}

// ============================================================
// 주도주
// ============================================================

// 가격은 통화마다 롤링 컴포넌트가 달라 그린 채로 받는다. 거래대금도 통화마다 단위가 달라 글자로 받는다
type Item = {
  key: string;
  name: string;
  code: string;
  price: ReactNode;
  rate: number;
  value: number; // 막대 길이용 원값
  valueLabel: string;
};

/** 달러 거래대금 → "$1.23B" / "$456M". 칸 폭에 전체 자릿수가 안 들어간다. */
function compactUsd(v: number): string {
  if (v >= 1e9) return `$${(v / 1e9).toFixed(2)}B`;
  if (v >= 1e6) return `$${(v / 1e6).toFixed(1)}M`;
  return `$${Math.round(v).toLocaleString("en-US")}`;
}

/** 키움 마스터 코드 — "009150_AL" 같이 거래소 접미사가 붙으면 앞쪽 6자리만. */
const shortCode = (code: string) => (code.includes("_") ? code.slice(0, code.indexOf("_")) : code);

/**
 * 첫 화면 주도주 카드.
 *
 * **목록 화면에서 고른 등락률과 무관하다.** 여기는 "오늘 뭐가 주도주냐" 하나만 답하는
 * 자리라, 보는 사람이 어떤 기준을 걸어뒀는지에 따라 답이 달라지면 안 된다. 순서와
 * 종목 선정은 서버가 정한다(거래대금·등락률 두 축의 백분위 기하평균).
 */
type LeadersProps = { live: boolean; date: string; clock: string };

function DomesticLeaders({ live, date, clock }: LeadersProps) {
  const { data, isLoading } = useLeadingStockLeaders();
  const items: Item[] = (data?.leaders ?? []).map((s) => ({
    key: s.stockCode,
    name: s.stockName,
    code: shortCode(s.stockCode),
    price: <NumWon value={s.currentPrice} />,
    rate: s.priceChangeRate,
    value: s.accumulatedTradingValue,
    valueLabel: formatKoreanMoney(s.accumulatedTradingValue),
  }));

  return (
    <LeaderCard
      title="국내 주도주"
      date={date}
      clock={clock}
      market="domestic"
      live={live}
      loading={isLoading}
      items={items}
      limitUps={data?.limitUps ?? []}
    />
  );
}

function OverseasLeaders({ live, date, clock }: LeadersProps) {
  const { data, isLoading } = useOverseasLeaders();
  const items: Item[] = (data ?? []).map((s) => ({
    key: `${s.exchange}:${s.symbol}`,
    name: s.name,
    code: s.symbol,
    price: <NumUsd value={s.price} prefix="" />,
    rate: s.rate,
    value: s.tradingValue,
    valueLabel: compactUsd(s.tradingValue),
  }));

  return (
    <LeaderCard
      title="해외 주도주"
      date={date}
      clock={clock}
      market="overseas"
      live={live}
      loading={isLoading}
      items={items}
    />
  );
}

/** 카드 머리 — 제목·기준 / 오른쪽 "전체 보기". 홈의 카드 넷이 같이 쓴다. */
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

/** 데스크톱 줄의 열 — 순위 · 종목 · 거래대금 · 현재가 · 등락률. 머리와 줄이 같은 값을 쓴다. */
const LEADER_COLS = "sm:grid-cols-[1rem_minmax(0,1fr)_7rem_6rem_4.5rem]";

/**
 * 누르면 주도주 필터 화면으로 — 해당 쪽(국내/해외)이 열린 채로.
 *
 * 종목 상세로 바로 보내지 않는다. 상세는 로그인 뒤라, 첫 화면에서 누르자마자
 * 벽을 만나게 된다. 어느 쪽이 먼저 오는지는 순서로만 가린다 — 뒤쪽을 흐리게 하면 읽기만 나빠진다.
 */
function LeaderCard({
  title,
  date,
  clock,
  market,
  live,
  loading,
  items,
  limitUps = [],
}: {
  title: string;
  date: string;
  clock: string;
  market: StockMarket;
  live: boolean;
  loading: boolean;
  items: Item[];
  /** 후보 풀 안의 상한가. 해외는 제한폭 자체가 없어 늘 비어 있다. */
  limitUps?: LimitUpItem[];
}) {
  const max = Math.max(1, ...items.map((s) => s.value));
  return (
    <Link to="/leading-stocks" onClick={() => rememberMarket(market)} className={cardCls}>
      <CardHead
        title={title}
        more="전체 보기"
        sub={
          <>
            <span className="num text-xs text-zinc-500">{date}</span>
            {/* 시각은 "장중"에만 붙인다 — 마감 뒤 칩은 "마감 기준"이라, 옆에 지금 시각이 있으면
                마감 시각으로 읽힌다 */}
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
        // 개장 전이거나, 오른 종목이 한 종목도 없는 날. 머리만 남지 않게 한 줄 둔다.
        <p className="px-3.5 py-7 text-center text-xs text-zinc-500">아직 주도주가 없습니다</p>
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

      {/* 상한가 — **없는 날은 줄째 사라진다.** 후보 컷이 거래대금 35위라 0건이 기본값이라,
          "없음"을 매일 적으면 죽은 줄 하나가 카드에 상주한다. */}
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
    </Link>
  );
}

/** 넓으면 열 맞춘 한 줄(거래대금 막대 포함), 좁으면 왼쪽 이름·거래대금 / 오른쪽 현재가·등락률. */
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
      </span>
      <span className="hidden flex-col items-end gap-1.5 sm:flex">
        <span className="num text-xs text-zinc-400">{item.valueLabel}</span>
        <span className="relative h-1 w-full overflow-hidden rounded-full bg-zinc-850">
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

// ============================================================
// 지금 움직임 — 최근 시그널 · 돌파 임박
// ============================================================

/** 최근 거래일을 거꾸로 짚어 가며 시그널이 있는 첫 날을 찾는다 — 주말·연휴에 빈 카드가 뜨지 않게. */
const SIGNAL_LOOKBACK = 7;

function useLatestSignals() {
  return useQuery({
    queryKey: ["home", "latest-signals"],
    queryFn: async () => {
      const day = new Date();
      for (let i = 0; i < SIGNAL_LOOKBACK; i++) {
        if (day.getDay() !== 0 && day.getDay() !== 6) {
          const iso = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, "0")}-${String(day.getDate()).padStart(2, "0")}`;
          const res = await apiFetch<SignalEventsResponse>(`/api/leading-stocks/signal-events?date=${iso}`);
          if (res.events.length > 0) return res;
        }
        day.setDate(day.getDate() - 1);
      }
      return null;
    },
    refetchInterval: 10_000,
  });
}

/** 시그널 화면의 줄과 같은 두 줄 — 위 시각·유형·종목·현재가·등락률, 아래 무엇이 일어났는지. */
function RecentSignals() {
  const { data, isLoading } = useLatestSignals();
  const events = (data?.events ?? []).slice(0, PREVIEW_ROWS);
  return (
    <Link to="/signal-log" className={cardCls}>
      <CardHead
        title="최근 시그널"
        more="전체 보기"
        sub={
          data && (
            <span className="num text-xs text-zinc-500">
              {dayLabel(data.date)} · {data.totalCount}건
            </span>
          )
        }
      />
      {isLoading ? (
        <div className="space-y-2 px-4 pb-3">
          {Array.from({ length: PREVIEW_ROWS }).map((_, i) => (
            <Skeleton key={i} className="h-9 w-full" />
          ))}
        </div>
      ) : events.length === 0 ? (
        <p className="px-3.5 py-7 text-center text-xs text-zinc-500">최근 시그널이 없습니다</p>
      ) : (
        events.map((e, i) => {
          const meta = EVENT_META[e.eventType];
          return (
            <div key={`${e.stockCode}-${e.occurredAt}-${i}`} className="flex flex-col gap-1 border-t border-zinc-800 px-3.5 py-2.5 sm:px-[18px]">
              <span className="flex items-center gap-2">
                <span className="num w-[3.75rem] shrink-0 text-xs text-zinc-500">{clockOf(e.occurredAt)}</span>
                <span className={`shrink-0 rounded px-1.5 py-0.5 text-[11px] ${meta.chip}`}>{meta.label}</span>
                <span className="min-w-0 truncate text-[13.5px] text-zinc-100">{e.stockName}</span>
                <span className="ml-auto flex shrink-0 items-baseline gap-2.5">
                  <span className="num text-[13.5px] text-zinc-100">{formatPrice(e.currentPrice)}</span>
                  <ProfitText value={e.priceChangeRate / 100} format={formatPct} className="num min-w-14 whitespace-nowrap text-right text-xs" />
                </span>
              </span>
              <span className="num truncate pl-[4.25rem] text-xs text-zinc-300">{detailOf(e)}</span>
            </div>
          );
        })
      )}
    </Link>
  );
}

/** 돌파선 3% 안에 든 종목 — 가까운 순. 눌림·돌파 화면의 "돌파" 모드와 같은 목록의 앞 세 줄이다. */
function NearBreakout() {
  const { data, isLoading } = useBreakoutRadar("resistance");
  const stocks = (data?.stocks ?? []).slice(0, PREVIEW_ROWS);
  return (
    <Link to="/breakout-radar" className={cardCls}>
      <CardHead title="돌파 임박" more="전체 보기" sub={<span className="text-xs text-zinc-500">돌파선 3% 이내 · 가까운 순</span>} />
      {isLoading ? (
        <div className="space-y-2 px-4 pb-3">
          {Array.from({ length: PREVIEW_ROWS }).map((_, i) => (
            <Skeleton key={i} className="h-9 w-full" />
          ))}
        </div>
      ) : stocks.length === 0 ? (
        <p className="px-3.5 py-7 text-center text-xs text-zinc-500">돌파선 가까이 온 종목이 없습니다</p>
      ) : (
        stocks.map((s) => {
          // 키움 분봉 cntr_tm이 HTS보다 1분 이르게 라벨링돼 고점은 +1분 보정한다(눌림·돌파 화면과 같다)
          const peak = new Date(s.peakAt);
          peak.setMinutes(peak.getMinutes() + 1);
          const at = `${peak.getDate()}일 ${String(peak.getHours()).padStart(2, "0")}:${String(peak.getMinutes()).padStart(2, "0")}`;
          return (
            <div key={s.stockCode} className="flex items-center gap-3 border-t border-zinc-800 px-3.5 py-2.5 sm:px-[18px]">
              <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                <span className="truncate text-[13.5px] text-zinc-100">{s.stockName}</span>
                <span className="num text-[11px] text-zinc-500">
                  돌파선 {formatPrice(s.peakPrice)} · {at}
                </span>
              </span>
              <span className="num shrink-0 text-[13.5px] text-orange-400">
                {s.gapRate <= 0 ? "돌파" : `${s.gapRate.toFixed(2)}% 남음`}
              </span>
              <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num w-14 shrink-0 text-right text-xs" />
            </div>
          );
        })
      )}
    </Link>
  );
}

/** 카드 한 장에 올리는 미리보기 줄 수 — 비로그인이 서버에서 받는 수와 같다. */
const PREVIEW_ROWS = 3;

/** yyyy-MM-dd → "09-23(수)" */
function dayLabel(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return `${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}(${"일월화수목금토"[new Date(y, m - 1, d).getDay()]})`;
}

// ============================================================
// 오늘의 수급
// ============================================================

/** 카드 한 장에 세로로 쌓이는 네 줄. 순서는 화면에서 읽는 순서다. */
const INVESTORS = [
  { key: "individual", label: "개인" },
  { key: "foreign", label: "외인" },
  { key: "institution", label: "기관" },
  { key: "otherCorp", label: "기타법인" },
] as const;

const MARKET_NAME: Record<string, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };

/**
 * 현물은 억원, 선물은 계약 — 한 목록에 있어도 단위가 달라 서로 견주지 않는다.
 *
 * `slug`는 `lib/indices.ts`가 정의한 것과 같아야 한다 — 카드를 누르면 그 지수의
 * 지수·수급 화면으로 간다.
 */
function netLabel(item: TodayNetItem): { name: string; unit: string; slug: string } {
  const name = MARKET_NAME[item.market] ?? item.market;
  const slug = item.market.toLowerCase();
  return item.futures
    ? { name: `${name} 선물`, unit: "계약", slug: `${slug}-futures` }
    : { name, unit: "억원", slug };
}

/**
 * 오늘의 수급 — 코스피·코스닥 현물과 두 지수선물의 당일 누적 투자자 순매수.
 *
 * **로그인 뒤에만 보인다.** 수급은 공개 API가 아니라(`auth/gate.py`), 게스트에게 띄우면
 * 빈 카드 네 장만 남는다. 주도주 아래에 두는 것도 같은 이유다 — 첫 화면의 주인공은
 * "오늘 뭐가 주도주냐"고, 수급은 그다음에 보는 재료다.
 */
function TodayNets({ live, clock }: { live: boolean; clock: string }) {
  const { data: me } = useMe();
  const authenticated = !!me?.authenticated;
  const { data, isLoading } = useTodayNets(authenticated);

  // 비로그인은 빈 카드 대신 이 자리가 무엇인지 말하고 로그인으로 잇는다
  if (!authenticated) {
    return (
      <div className="flex flex-col items-center gap-2.5 rounded-2xl border border-dashed border-zinc-800 px-4 py-5 text-center">
        <span className="text-[13.5px] font-semibold text-zinc-100">오늘의 수급은 로그인 후에 보입니다</span>
        <span className="text-xs text-zinc-500">코스피·코스닥 현물과 선물의 투자자별 순매수</span>
        <GoogleLoginButton />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <h2 className="text-[15px] font-bold text-zinc-100">오늘의 수급</h2>
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

function NetCard({ item }: { item: TodayNetItem }) {
  const { name, unit, slug } = netLabel(item);
  const nets = item.nets;
  // 막대 길이는 그 카드 안에서만 뜻이 있다 — 가장 큰 값이 반칸을 꽉 채운다
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

/**
 * 0선을 가운데 두고 순매수는 오른쪽, 순매도는 왼쪽으로 뻗는다.
 *
 * `value`가 null이면 그 줄만 대시로 비운다 — 줄 높이는 그대로라 카드가 들썩이지 않는다.
 */
function NetRow({ label, value, top }: { label: string; value: number | null; top: number }) {
  // 반칸(50%) 기준 — 0이 아닌 값은 최소 한 줄이라도 보이게 바닥을 둔다
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
