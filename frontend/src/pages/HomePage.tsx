import { Link } from "react-router-dom";
import {
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
import { formatPct, formatPrice, formatUsd } from "../lib/format";
import SessionStrip from "../components/layout/SessionStrip";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import GoogleLoginButton from "../components/common/GoogleLoginButton";
import type { TodayNetItem } from "../types";

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
  const krHoliday = useMarketCalendarStatus("KR").data?.isHoliday;
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
  // 주인공 자리는 시각이 정한다 — 평일 08:00~19:59만 국내고 나머지는 해외다.
  // 지수 타일도 같은 규칙을 쓴다(`overseasIsMain`).
  const domesticFirst = !overseasIsMain(now);

  // 두 쪽 날짜가 다를 수 있다 — 해외는 미국 현지 거래일이라 한국 오전에는 하루 뒤처진다
  const clock = formatClock(now);
  const domestic = (
    <DomesticLeaders
      live={domesticLive}
      first={domesticFirst}
      date={formatTradingDay(krTradingDay(now, !!krHoliday))}
      clock={clock}
    />
  );
  const overseas = (
    <OverseasLeaders
      live={overseasLive}
      first={!domesticFirst}
      date={formatTradingDay(usTradingDay(now, !!usHoliday))}
      clock={clock}
    />
  );

  return (
    <div className="space-y-5">
      <SessionStrip size="lg" />
      <IndexTiles domesticOpen={domesticOpen} overseasOpen={overseasOpen} now={now} />

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

      <TodayNets live={domesticOpen} clock={clock} />

      <Pitch />
    </div>
  );
}

// ============================================================
// 지수
// ============================================================

function IndexTiles({
  domesticOpen,
  overseasOpen,
  now,
}: {
  domesticOpen: boolean;
  overseasOpen: boolean;
  now: Date;
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
            ? `전일 종가 ${formatPrice(night.data.dayClose)} · 갭 ${night.data.gap.toFixed(2)}`
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
  const order = overseasIsMain(now)
    ? [tiles.nasdaq, tiles.night, tiles.kospi, tiles.kosdaq]
    : [tiles.kospi, tiles.kosdaq, tiles.night, tiles.nasdaq];

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2.5">
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
      className="block rounded-xl bg-zinc-900 px-4 py-3.5 min-w-0 transition-opacity hover:opacity-90"
    >
      <div className="flex items-center gap-1.5 text-sm text-zinc-500">
        <span className="text-zinc-200">{label}</span>
        {/* 장중 칩은 주도주 카드의 "장중 14:07"과 같은 색이다 — 한 화면에서 같은 뜻이
            다른 색으로 보이면, 둘이 다른 상태를 가리키는 줄 읽는다 */}
        {tag && (
          <span
            className={
              tag === "장중"
                ? "rounded-full bg-blue-50 px-2 py-px text-[11px] font-medium text-blue-700"
                : "rounded border border-zinc-800 px-1 text-[11.5px] text-zinc-500"
            }
          >
            {tag}
          </span>
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
 * 첫 화면 주도주 카드.
 *
 * **목록 화면에서 고른 등락률과 무관하다.** 여기는 "오늘 뭐가 주도주냐" 하나만 답하는
 * 자리라, 보는 사람이 어떤 기준을 걸어뒀는지에 따라 답이 달라지면 안 된다. 순서와
 * 종목 선정은 서버가 정한다(거래대금·등락률 두 축의 백분위 기하평균).
 */
type LeadersProps = { live: boolean; first: boolean; date: string; clock: string };

function DomesticLeaders({ live, first, date, clock }: LeadersProps) {
  const { data, isLoading } = useLeadingStockLeaders();
  const items: Item[] = (data ?? []).map((s) => ({
    key: s.stockCode,
    name: s.stockName,
    price: formatPrice(s.currentPrice),
    rate: s.priceChangeRate,
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
    />
  );
}

function OverseasLeaders({ live, first, date, clock }: LeadersProps) {
  const { data, isLoading } = useOverseasLeaders();
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
      clock={clock}
      market="overseas"
      live={live}
      first={first}
      loading={isLoading}
      items={items}
    />
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
  date,
  clock,
  market,
  live,
  first,
  loading,
  items,
}: {
  title: string;
  date: string;
  clock: string;
  market: StockMarket;
  live: boolean;
  first: boolean;
  loading: boolean;
  items: Item[];
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
          {/* 시각은 "장중"에만 붙인다 — 마감 뒤 칩은 "마감 기준"이라, 옆에 지금 시각이 있으면
              마감 시각으로 읽힌다 */}
          {live && (
            <span className="rounded-full bg-blue-50 px-2 py-px text-[10.5px] font-medium text-blue-700">
              장중 <span className="num">{clock}</span>
            </span>
          )}
          {!live && <span className="text-[11px] font-normal text-zinc-500">마감 기준</span>}
        </span>
        {/* 기준을 말하던 자리다. 홈이 자기 규칙으로 뽑으니 말할 기준이 없어, 링크라는 것만 남긴다 */}
        <span className="flex items-center gap-1 whitespace-nowrap rounded-full bg-elevated px-2.5 py-0.5 text-[11.5px] text-zinc-400">
          전체 보기
          <span aria-hidden className="opacity-60">›</span>
        </span>
      </div>

      {loading ? (
        <div className="space-y-2 p-4">
          {Array.from({ length: LEADERS_COUNT }).map((_, i) => (
            <Skeleton key={i} className="h-5 w-full" />
          ))}
        </div>
      ) : items.length === 0 ? (
        // 개장 전이거나, 오른 종목이 한 종목도 없는 날. 머리만 남지 않게 한 줄 둔다.
        <p className="px-3.5 py-7 text-center text-xs text-zinc-500">아직 주도주가 없습니다</p>
      ) : (
        <div className="divide-y divide-zinc-800/60">
          {items.map((s, i) => (
            <Row key={s.key} rank={i + 1} name={s.name} symbol={s.symbol} price={s.price} rate={s.rate} />
          ))}
        </div>
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

  if (!authenticated) return null;

  return (
    <div>
      <div className="mb-2.5 flex items-center gap-2">
        <span className="text-sm font-bold">오늘의 수급</span>
        {live && (
          <span className="rounded-full bg-blue-50 px-2 py-px text-[10.5px] font-medium text-blue-700">
            장중 <span className="num">{clock}</span>
          </span>
        )}
        <span className="ml-auto text-[11.5px] text-zinc-500">
          순매수 <span className="text-red-400">빨강</span> · 순매도{" "}
          <span className="text-blue-400">파랑</span>
        </span>
      </div>

      {isLoading ? (
        <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-[8.75rem] w-full rounded-xl" />
          ))}
        </div>
      ) : !data || data.length === 0 ? (
        <p className="rounded-xl bg-zinc-900 px-4 py-7 text-center text-xs text-zinc-500">
          아직 오늘 수급이 없습니다
        </p>
      ) : (
        <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 lg:grid-cols-4">
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
      className="block rounded-xl bg-zinc-900 px-4 py-3.5 min-w-0 transition-opacity hover:opacity-90"
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

// ============================================================
// 소개 띠
// ============================================================

/** 로그인 전에만 보인다 — 이미 아는 이야기를 매일 읽힐 이유가 없다. */
function Pitch() {
  const { data: me } = useMe();
  if (me?.authenticated) return null;

  return (
    <div className="flex flex-col items-center gap-3 border-t border-zinc-800 pt-6 pb-2">
      <GoogleLoginButton />
      {/* 폭까지 풀지는 않는다 — 넓은 화면에서 한 줄이 너무 길어져 읽기 나빠진다 */}
      <p className="max-w-[56ch] text-center text-[13px] text-zinc-500">
        로그인하면 주도주 시그널과 여정, 눌림·돌파 페이지 전체를 확인할 수 있습니다.
      </p>
    </div>
  );
}
