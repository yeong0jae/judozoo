import { Link } from "react-router-dom";
import {
  useKospiIndex,
  useKosdaqIndex,
  useLeadingStockCandidates,
  useMarketCalendarStatus,
  useNasdaqIndexQuote,
  useNightFuturesQuote,
  useOverseasRanking,
} from "../api/queries";
import { useMe } from "../api/auth";
import {
  formatTradingDay,
  krTradingDay,
  usTradingDay,
  useMarketSessions,
} from "../lib/marketSession";
import { useMinChangeRate, useOverseasMinChangeRate } from "../lib/changeRate";
import { rememberMarket, type StockMarket } from "../lib/stockMarket";
import { ALWAYS_INCLUDED_RANKS } from "../lib/leadingStock";
import { formatPct, formatPrice, formatUsd } from "../lib/format";
import SessionStrip from "../components/layout/SessionStrip";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import GoogleLoginButton from "../components/common/GoogleLoginButton";

/** 위 구간(등락률 기준 통과)은 최대 4줄. 아래 구간은 거래대금 강제 포함분 전부. */
const TOP_PASSED = 4;

/**
 * 첫 화면 — 지금 시장이 어떤지만 보여준다.
 *
 * 로그인 없이 볼 수 있는 것으로만 채운다(지수·야간선물·양쪽 주도주 목록).
 * 장이 도는 쪽이 왼쪽 주인공 자리에 오고, 쉬는 쪽은 흐리게 내려간다 —
 * 밤에 보는 국내 목록은 살아 있는 숫자가 아니라서다.
 */
export default function HomePage() {
  const { kr, us } = useMarketSessions();
  const krHoliday = useMarketCalendarStatus("KR").data?.isHoliday;
  const usHoliday = useMarketCalendarStatus("US").data?.isHoliday;

  const domesticLive = kr !== null && !krHoliday;
  const overseasLive = us !== null && !usHoliday;
  // 양쪽 다 쉬는 주말·새벽에는 국내를 앞에 둔다 — 여기는 국내 단타 화면이다
  const domesticFirst = domesticLive || !overseasLive;

  // 두 쪽 날짜가 다를 수 있다 — 해외는 미국 현지 거래일이라 한국 오전에는 하루 뒤처진다
  const now = new Date();
  const domestic = (
    <DomesticLeaders
      live={domesticLive}
      first={domesticFirst}
      date={formatTradingDay(krTradingDay(now, !!krHoliday))}
    />
  );
  const overseas = (
    <OverseasLeaders
      live={overseasLive}
      first={!domesticFirst}
      date={formatTradingDay(usTradingDay(now, !!usHoliday))}
    />
  );

  return (
    <div className="space-y-5">
      <SessionStrip size="lg" />
      <IndexTiles domesticLive={domesticLive} overseasLive={overseasLive} />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
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

      <Pitch />
    </div>
  );
}

// ============================================================
// 지수
// ============================================================

function IndexTiles({
  domesticLive,
  overseasLive,
}: {
  domesticLive: boolean;
  overseasLive: boolean;
}) {
  const kospi = useKospiIndex();
  const kosdaq = useKosdaqIndex();
  const night = useNightFuturesQuote();
  const nasdaq = useNasdaqIndexQuote();
  const tag = domesticLive ? "장중" : "종가";

  return (
    // 슬러그는 lib/indices.ts가 정의한 것과 같아야 한다
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2.5">
      <Tile
        label="코스피"
        slug="kospi"
        tag={tag}
        value={kospi.data?.currentValue}
        rate={kospi.data?.changeRate}
      />
      <Tile
        label="코스닥"
        slug="kosdaq"
        tag={tag}
        value={kosdaq.data?.currentValue}
        rate={kosdaq.data?.changeRate}
      />
      <Tile
        label="코스피 야간 선물"
        slug="night-futures"
        value={night.data?.price}
        rate={night.data?.changeRate}
        sub={
          night.data
            ? `전일 종가 ${formatPrice(night.data.dayClose)} · 갭 ${night.data.gap.toFixed(2)}`
            : undefined
        }
      />
      {/* 국내 셋 뒤에 둔다 — 여기는 국내 단타 화면이고, 나스닥은 밤사이 분위기를 재는 참고값이다 */}
      <Tile
        label="나스닥"
        slug="nasdaq"
        tag={overseasLive ? "장중" : "종가"}
        value={nasdaq.data?.price}
        rate={nasdaq.data?.changeRate}
      />
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
      className="block rounded-xl bg-zinc-900 px-4 py-3.5 min-w-0 transition-opacity hover:opacity-90"
    >
      <div className="flex items-center gap-1.5 text-sm text-zinc-500">
        <span className="text-zinc-200">{label}</span>
        {tag && (
          <span className="rounded border border-zinc-800 px-1 text-[11.5px] text-zinc-500">{tag}</span>
        )}
      </div>
      {value === undefined || rate === undefined ? (
        <Skeleton className="mt-1.5 h-7 w-28" />
      ) : (
        <>
          <div className="num text-xl font-bold leading-snug">
            <ProfitText value={rate} format={() => value.toLocaleString("ko-KR")} />
          </div>
          <div className="num text-[15px] font-medium">
            <ProfitText value={rate} format={(v) => formatPct(v / 100)} />
          </div>
        </>
      )}
      {sub && <div className="num mt-0.5 text-[11px] text-zinc-500">{sub}</div>}
    </Link>
  );
}

// ============================================================
// 주도주
// ============================================================

type Item = { key: string; name: string; symbol?: string; price: string; rate: number };

/**
 * 홈 카드에 올릴 두 묶음을 만든다.
 *
 * **아래는 거래대금 1~2위로 고정한다.** 등락률과 무관하게 봐야 할 종목이라
 * 백엔드가 목록에 강제로 넣는 것이고, 기준을 바꿨다고 사라지면 안 된다.
 * 응답이 거래대금 내림차순이라 앞 두 개가 곧 1·2위다.
 *
 * **위는 등락률 기준을 통과한 것 중 거래대금 순으로 최대 넷.** 목록 화면과 같은 순서라
 * "전체 보기"로 넘어갔을 때 줄이 뒤바뀌지 않는다. 아래에 이미 있는 종목은 뺀다
 * (두 번 나오지 않게).
 */
type LeadersProps = { live: boolean; first: boolean; date: string };

function split(items: Item[], threshold: number) {
  const byValue = items.slice(0, ALWAYS_INCLUDED_RANKS);
  const shown = new Set(byValue.map((i) => i.key));
  const passed = items
    .filter((i) => i.rate >= threshold && !shown.has(i.key))
    .slice(0, TOP_PASSED);
  return { passed, byValue };
}

function DomesticLeaders({ live, first, date }: LeadersProps) {
  const [minChangeRate] = useMinChangeRate();
  const { data, isLoading } = useLeadingStockCandidates(minChangeRate);
  const items: Item[] = (data?.stocks ?? []).map((s) => ({
    key: s.stockCode,
    name: s.stockName,
    price: formatPrice(s.currentPrice),
    rate: s.priceChangeRate,
  }));

  return (
    <LeaderCard
      title="국내 주도주"
      date={date}
      market="domestic"
      live={live}
      first={first}
      loading={isLoading}
      threshold={minChangeRate}
      {...split(items, minChangeRate)}
    />
  );
}

function OverseasLeaders({ live, first, date }: LeadersProps) {
  const [minChangeRate] = useOverseasMinChangeRate();
  const { data, isLoading } = useOverseasRanking(minChangeRate);
  const items: Item[] = (data ?? []).map((s) => ({
    key: `${s.exchange}:${s.symbol}`,
    name: s.name,
    symbol: s.symbol,
    price: `$${formatUsd(s.price)}`,
    rate: s.rate,
  }));

  return (
    <LeaderCard
      title="해외 주도주"
      date={date}
      market="overseas"
      live={live}
      first={first}
      loading={isLoading}
      threshold={minChangeRate}
      {...split(items, minChangeRate)}
    />
  );
}

/** "+7%" / "-7%" / "0%" — 칩과 구분선이 같은 표기를 쓴다. */
const formatThreshold = (rate: number) => `${rate > 0 ? "+" : ""}${rate}%`;

/**
 * 등락률 칩의 색 — 한국 거래소 관행대로 양수 빨강 / 음수 파랑 / 0 중립.
 * 목록 화면 선택기의 **선택된 칸과 같은 색**이라, 둘이 같은 값이라는 게 눈으로 이어진다.
 */
function chipTone(rate: number): string {
  if (rate > 0) return "bg-red-500/15 text-red-700";
  if (rate < 0) return "bg-blue-500/15 text-blue-700";
  return "bg-elevated text-zinc-200";
}

/**
 * 목록 한 칸. 누르면 주도주 필터 화면으로 — 해당 쪽(국내/해외)이 열린 채로.
 *
 * 종목 상세로 바로 보내지 않는다. 상세는 로그인 뒤라, 첫 화면에서 누르자마자
 * 벽을 만나게 된다.
 *
 * 등락률 칩은 지금 걸린 기준을 말한다. 홈이 기준을 따로 갖지 않고 목록 화면과
 * 같은 값을 쓰기 때문에, 그 값을 안 보여주면 "목록이랑 왜 다르지"가 된다.
 */
function LeaderCard({
  title,
  date,
  market,
  live,
  first,
  loading,
  threshold,
  passed,
  byValue,
}: {
  title: string;
  date: string;
  market: StockMarket;
  live: boolean;
  first: boolean;
  loading: boolean;
  threshold: number;
  passed: Item[];
  byValue: Item[];
}) {
  return (
    <Link
      to="/leading-stocks"
      onClick={() => rememberMarket(market)}
      className={`block rounded-xl bg-zinc-900 overflow-hidden min-w-0 transition-opacity hover:opacity-90 ${
        first ? "outline outline-1 outline-emerald-700/40" : "opacity-70"
      }`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-800 px-3.5 py-3">
        <span className="flex min-w-0 items-center gap-2 text-sm font-bold">
          {/* 날짜와 제목은 한 덩어리로 읽힌다 — 좁은 화면에서도 둘 사이가 갈라지지 않게 */}
          <span className="whitespace-nowrap">
            <span className="mr-1.5 text-[12.5px] font-normal text-zinc-400">{date}</span>
            {title}
          </span>
          {live && (
            <span className="rounded-full bg-blue-50 px-2 py-px text-[10.5px] font-medium text-blue-700">
              장중
            </span>
          )}
          {!live && <span className="text-[11px] font-normal text-zinc-500">마감 기준</span>}
        </span>
        <span
          className={`flex items-center gap-1 whitespace-nowrap rounded-full px-2.5 py-0.5 text-[11.5px] ${chipTone(threshold)}`}
        >
          {/* 숫자에만 mono를 건다 — 한글이 섞인 채로 걸면 한 칩 안에서 글꼴이 갈린다 */}
          <span className="num">{formatThreshold(threshold)}</span> 이상
          <span aria-hidden className="opacity-60">›</span>
        </span>
      </div>

      {loading ? (
        <div className="space-y-2 p-4">
          {Array.from({ length: TOP_PASSED + ALWAYS_INCLUDED_RANKS }).map((_, i) => (
            <Skeleton key={i} className="h-5 w-full" />
          ))}
        </div>
      ) : passed.length === 0 && byValue.length === 0 ? (
        // 목록이 통째로 비는 경우 — 개장 전이나 조회 실패. 머리만 남지 않게 한 줄 둔다.
        <p className="px-3.5 py-7 text-center text-xs text-zinc-500">아직 후보가 없습니다</p>
      ) : (
        <>
          <div className="divide-y divide-zinc-800/60">
            {passed.map((s, i) => (
              <Row key={s.key} rank={i + 1} name={s.name} symbol={s.symbol} price={s.price} rate={s.rate} />
            ))}
          </div>

          {byValue.length > 0 && (
            <>
              {/* 등락률과 무관하게 늘 보여주는 자리 — 기준을 바꿔도 이 둘은 남는다 */}
              <div className="flex items-center gap-2 border-t border-zinc-800/60 bg-zinc-850/40 px-3.5 py-1.5">
                <span className="text-[10.5px] text-zinc-500 whitespace-nowrap">
                  {passed.length === 0 ? "주도주 없음 · 거래대금 상위" : "거래대금 상위"}
                </span>
                <span className="h-px flex-1 bg-zinc-800" />
              </div>
              <div className="divide-y divide-zinc-800/60 bg-zinc-850/25 opacity-60">
                {byValue.map((s, i) => (
                  <Row key={s.key} rank={i + 1} name={s.name} symbol={s.symbol} price={s.price} rate={s.rate} />
                ))}
              </div>
            </>
          )}
        </>
      )}
    </Link>
  );
}

function Row({ rank, name, symbol, price, rate }: Omit<Item, "key"> & { rank: number }) {
  return (
    <div className="grid grid-cols-[1.25rem_minmax(0,1fr)_auto_auto] items-baseline gap-2.5 px-4 py-3 text-[13.5px]">
      <span className="num text-[11.5px] text-zinc-500">{rank}</span>
      <span className="truncate text-[15px] text-zinc-200">
        {name}
        {symbol && <span className="num ml-1.5 text-[11px] text-zinc-500">{symbol}</span>}
      </span>
      <span className="num text-xs text-zinc-500">{price}</span>
      <span className="num min-w-[3.6rem] text-right text-xs font-medium">
        <ProfitText value={rate} format={(v) => formatPct(v / 100)} />
      </span>
    </div>
  );
}

// ============================================================
// 소개 띠
// ============================================================

/** 로그인 전에만 보인다 — 이미 아는 이야기를 매일 읽힐 이유가 없다. */
function Pitch() {
  const { data: me } = useMe();
  if (me?.authenticated) return null;

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-zinc-800 pt-4">
      <p className="max-w-[56ch] text-[13px] text-zinc-500">
        종목을 누르면 필터 평가와 분봉, 일봉, 투자자 수급까지 봅니다. 로그인하면 확인할 수
        있습니다.
      </p>
      <GoogleLoginButton />
    </div>
  );
}
