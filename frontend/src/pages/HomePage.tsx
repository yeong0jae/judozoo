import { Link } from "react-router-dom";
import type { ReactNode } from "react";
import {
  useKospiIndex,
  useKosdaqIndex,
  useLeadingStockCandidates,
  useMarketCalendarStatus,
  useNightFuturesQuote,
  useOverseasRanking,
} from "../api/queries";
import { useMe } from "../api/auth";
import { useMarketSessions } from "../lib/marketSession";
import { useMinChangeRate, useOverseasMinChangeRate } from "../lib/changeRate";
import { rememberMarket, type StockMarket } from "../lib/stockMarket";
import { formatPct, formatPrice, formatUsd } from "../lib/format";
import SessionStrip from "../components/layout/SessionStrip";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import GoogleLoginButton from "../components/common/GoogleLoginButton";

const TOP_N = 5;

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

  const domestic = <DomesticLeaders live={domesticLive} first={domesticFirst} />;
  const overseas = <OverseasLeaders live={overseasLive} first={!domesticFirst} />;

  return (
    <div className="space-y-5">
      <SessionStrip size="lg" />
      <IndexTiles domesticLive={domesticLive} />

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

function IndexTiles({ domesticLive }: { domesticLive: boolean }) {
  const kospi = useKospiIndex();
  const kosdaq = useKosdaqIndex();
  const night = useNightFuturesQuote();
  const tag = domesticLive ? "장중" : "종가";

  return (
    <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
      <Tile label="코스피" tag={tag} value={kospi.data?.currentValue} rate={kospi.data?.changeRate} />
      <Tile label="코스닥" tag={tag} value={kosdaq.data?.currentValue} rate={kosdaq.data?.changeRate} />
      <Tile
        label="코스피 야간 선물"
        value={night.data?.price}
        rate={night.data?.changeRate}
        sub={
          night.data
            ? `전일 종가 ${formatPrice(night.data.dayClose)} · 갭 ${night.data.gap.toFixed(2)}`
            : undefined
        }
      />
    </div>
  );
}

function Tile({
  label,
  tag,
  value,
  rate,
  sub,
}: {
  label: string;
  tag?: string;
  value: number | undefined;
  rate: number | undefined;
  sub?: string;
}) {
  return (
    <div className="rounded-xl bg-zinc-900 px-4 py-3.5 min-w-0">
      <div className="flex items-center gap-1.5 text-xs text-zinc-500">
        <span>{label}</span>
        {tag && (
          <span className="rounded border border-zinc-800 px-1 text-[10px] text-zinc-500">{tag}</span>
        )}
      </div>
      {value === undefined || rate === undefined ? (
        <Skeleton className="mt-1.5 h-7 w-28" />
      ) : (
        <>
          <div className="num text-xl font-bold leading-snug">
            <ProfitText value={rate} format={() => value.toLocaleString("ko-KR")} />
          </div>
          <div className="num text-[13px] font-medium">
            <ProfitText value={rate} format={(v) => formatPct(v / 100)} />
          </div>
        </>
      )}
      {sub && <div className="num mt-0.5 text-[11px] text-zinc-500">{sub}</div>}
    </div>
  );
}

// ============================================================
// 주도주
// ============================================================

function DomesticLeaders({ live, first }: { live: boolean; first: boolean }) {
  const [minChangeRate] = useMinChangeRate();
  const { data, isLoading } = useLeadingStockCandidates(minChangeRate);
  const stocks = (data?.stocks ?? []).slice(0, TOP_N);

  return (
    <LeaderCard title="국내 주도주" market="domestic" live={live} first={first} loading={isLoading}>
      {stocks.map((s, i) => (
        <Row
          key={s.stockCode}
          rank={i + 1}
          name={s.stockName}
          price={formatPrice(s.currentPrice)}
          rate={s.priceChangeRate}
        />
      ))}
    </LeaderCard>
  );
}

function OverseasLeaders({ live, first }: { live: boolean; first: boolean }) {
  const [minChangeRate] = useOverseasMinChangeRate();
  const { data, isLoading } = useOverseasRanking(minChangeRate);
  const stocks = (data ?? []).slice(0, TOP_N);

  return (
    <LeaderCard title="해외 주도주" market="overseas" live={live} first={first} loading={isLoading}>
      {stocks.map((s, i) => (
        <Row
          key={`${s.exchange}:${s.symbol}`}
          rank={i + 1}
          name={s.name}
          symbol={s.symbol}
          price={`$${formatUsd(s.price)}`}
          rate={s.rate}
        />
      ))}
    </LeaderCard>
  );
}

/**
 * 목록 한 칸. 누르면 주도주 필터 화면으로 — 해당 쪽(국내/해외)이 열린 채로.
 *
 * 종목 상세로 바로 보내지 않는다. 상세는 로그인 뒤라, 첫 화면에서 누르자마자
 * 벽을 만나게 된다.
 */
function LeaderCard({
  title,
  market,
  live,
  first,
  loading,
  children,
}: {
  title: string;
  market: StockMarket;
  live: boolean;
  first: boolean;
  loading: boolean;
  children: ReactNode;
}) {
  return (
    <Link
      to="/leading-stocks"
      onClick={() => rememberMarket(market)}
      className={`block rounded-xl bg-zinc-900 overflow-hidden min-w-0 transition-opacity hover:opacity-90 ${
        first ? "outline outline-1 outline-emerald-700/40" : "opacity-70"
      }`}
    >
      <div className="flex items-center justify-between gap-2.5 border-b border-zinc-800 px-4 py-3">
        <span className="flex items-center gap-2 text-sm font-bold">
          {title}
          {live && (
            <span className="rounded-full bg-blue-50 px-2 py-px text-[10.5px] font-medium text-blue-700">
              장중
            </span>
          )}
          {!live && <span className="text-[11px] font-normal text-zinc-500">마감 기준</span>}
        </span>
        <span className="text-xs text-zinc-500 whitespace-nowrap">전체 보기 →</span>
      </div>

      {loading ? (
        <div className="space-y-2 p-4">
          {Array.from({ length: TOP_N }).map((_, i) => (
            <Skeleton key={i} className="h-5 w-full" />
          ))}
        </div>
      ) : (
        // 행 사이에만 선을 긋는다 — 첫 행에 테두리를 주면 헤더 선과 겹쳐 두 줄로 보인다
        <div className="divide-y divide-zinc-800/60">{children}</div>
      )}
    </Link>
  );
}

function Row({
  rank,
  name,
  symbol,
  price,
  rate,
}: {
  rank: number;
  name: string;
  symbol?: string;
  price: string;
  rate: number;
}) {
  return (
    <div className="grid grid-cols-[1.25rem_minmax(0,1fr)_auto_auto] items-baseline gap-2.5 px-4 py-2 text-[13.5px]">
      <span className="num text-[11.5px] text-zinc-500">{rank}</span>
      <span className="truncate text-zinc-200">
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
        종목을 누르면 필터 평가와 분봉, 일봉, 투자자 수급까지 봅니다. 구글 계정으로 로그인하면 바로
        열립니다.
      </p>
      <GoogleLoginButton />
    </div>
  );
}
