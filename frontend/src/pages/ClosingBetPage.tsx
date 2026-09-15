import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
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
import { colorByPnL, formatPct, formatPrice } from "../lib/format";
import type {
  FuturesOrgBreakdown,
  MacroQuote,
  MacroQuotes,
  MacroTarget,
  MarketInvestorDay,
  MarketType,
} from "../types";
import { marketDailySeries, marketMinuteSeries } from "../components/common/tossCandles";

/** 실시간이 아닌 시세임을 알리는 배지. */
function DelayBadge() {
  return (
    <span className="text-[10px] text-zinc-500 bg-zinc-800 rounded px-1 py-px">10분 지연</span>
  );
}

/**
 * 시황분석 — 장 막판 매수 판단용 지표 집약 대시보드.
 * 좌: 테마 관심목록 / 중앙: 선택 대상(종목·테마·지수) 상세 / 우: 종목 뉴스(종목을 골랐을 때만).
 */
function ClosingBetPageInner({ ix, authenticated }: { ix: IndexInfo; authenticated: boolean }) {
  useEffect(() => {
    rememberSlug(ix.slug);
  }, [ix.slug]);

  return (
    <div className="flex flex-col gap-4">
      <HolidayBanner />
      <MarketStrip currentSlug={ix.slug} />

      <div className="min-h-0 lg:h-[calc(100dvh-15rem)] lg:min-h-[40rem] lg:overflow-hidden">
        <AnimatePresence mode="wait">
          <motion.div
            key={ix.slug}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
            className="h-full min-h-0"
          >
            <SubjectDetail ix={ix} authenticated={authenticated} />
          </motion.div>
        </AnimatePresence>
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

/** 상단 시장 스트립 — 배경 없이 페이지에 얹히고, 동일폭 7칸을 얇은 구분선으로만 분리. */
function MarketStrip({ currentSlug }: { currentSlug: string }) {
  const kospi = useKospiIndex();
  const kosdaq = useKosdaqIndex();
  const futures = useFuturesQuote("KOSPI");
  const kosdaqFutures = useFuturesQuote("KOSDAQ");
  const night = useNightFuturesQuote();
  const nasdaqIndex = useNasdaqIndexQuote();
  const macro = useMacroQuotes();
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-7 divide-x divide-y lg:divide-y-0 divide-zinc-800">
      {INDICES.map((ix) => {
        const active = ix.slug === currentSlug;
        // 매크로만 지표 둘을 한 칸에 담아 다른 칸과 모양이 다르다.
        if (ix.id === "macro") {
          return (
            <MacroCell
              key={ix.id}
              ix={ix}
              quotes={macro.data}
              active={active}
            />
          );
        }
        const q =
          ix.id === "kospi" ? toCell(kospi.data?.currentValue, kospi.data?.changeRate)
          : ix.id === "kosdaq" ? toCell(kosdaq.data?.currentValue, kosdaq.data?.changeRate)
          : ix.id === "kospiF" ? toCell(futures.data?.futuresPrice, futures.data?.changeRate)
          : ix.id === "kosdaqF" ? toCell(kosdaqFutures.data?.futuresPrice, kosdaqFutures.data?.changeRate)
          : ix.id === "nightF" ? toCell(night.data?.price, night.data?.changeRate)
          : toCell(nasdaqIndex.data?.price, nasdaqIndex.data?.changeRate);
        return (
          <IndexCell
            key={ix.id}
            ix={ix}
            value={q?.value ?? null}
            pct={q?.pct ?? null}
            active={active}
          />
        );
      })}
    </div>
  );
}

/** 매크로 칸 — 원달러·WTI를 두 줄로 압축. 다른 칸보다 글자가 작다. */
function MacroCell({
  ix,
  quotes,
  active,
}: {
  ix: IndexInfo;
  quotes?: MacroQuotes;
  active: boolean;
}) {
  return (
    <Link
      to={`/market-analysis/${ix.slug}`}
      aria-current={active ? "page" : undefined}
      className={`flex flex-col px-3.5 py-3 text-left transition-colors ${
        active ? "bg-selected" : "hover:bg-zinc-850"
      }`}
    >
      <span className="text-[14px] font-medium text-zinc-300">{ix.name}</span>
      <div className="mt-0.5 flex flex-col gap-px">
        <MacroCellRow label="원달러" quote={quotes?.usdKrw} />
        {/* WTI만 CME 시세라 지연. 원달러(FX 현물)는 지연 대상이 아니다. */}
        <MacroCellRow label="WTI" quote={quotes?.wti} delayed />
        <MacroCellRow label="VIX" quote={quotes?.vix} />
      </div>
    </Link>
  );
}

/** 매크로 칸 한 줄 — 이름 · 값 · 등락률. 값이 없으면 대시. */
function MacroCellRow({
  label,
  quote,
  delayed,
}: {
  label: string;
  quote?: MacroQuote | null;
  delayed?: boolean;
}) {
  return (
    <div className="flex items-baseline gap-1.5 flex-wrap">
      <span className="text-[11px] text-zinc-500 w-[34px] shrink-0">{label}</span>
      {!quote ? (
        <span className="num text-[13px] font-bold text-zinc-700">—</span>
      ) : (
        <>
          <span className="num text-[13px] font-bold text-zinc-100">{fmt2(quote.price)}</span>
          <span className={`num text-[11px] font-medium ${colorByPnL(quote.changeRate)}`}>
            {formatPct(quote.changeRate / 100)}
          </span>
          {delayed && <DelayBadge />}
        </>
      )}
    </div>
  );
}

function IndexCell({
  ix,
  value,
  pct,
  active,
}: {
  ix: IndexInfo;
  value: number | null;
  pct: number | null;
  active: boolean;
}) {
  return (
    <Link
      to={`/market-analysis/${ix.slug}`}
      aria-current={active ? "page" : undefined}
      className={`flex flex-col px-3.5 py-3 text-left transition-colors ${
        active ? "bg-selected" : "hover:bg-zinc-850"
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
    </Link>
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
        <p className="text-xs text-zinc-400">{what}은 로그인하면 확인할 수 있습니다</p>
        <GoogleLoginButton />
      </div>
    </div>
  );
}

function SubjectDetail({ ix, authenticated }: { ix: IndexInfo; authenticated: boolean }) {
  // 야간선물·나스닥·매크로는 수급 표가 없어 그대로 둔다
  const detail =
    ix.id === "kospiF" ? <FuturesIndexDetail index={ix} market="KOSPI" authenticated={authenticated} />
    : ix.id === "kosdaqF" ? <FuturesIndexDetail index={ix} market="KOSDAQ" authenticated={authenticated} />
    : ix.id === "nightF" ? <NightFuturesDetail index={ix} />
    : ix.id === "nasdaq" ? <NasdaqIndexDetail index={ix} />
    : ix.id === "macro" ? <MacroDetail />
    : ix.id === "kosdaq" ? <LiveIndexDetail market="KOSDAQ" name={ix.name} authenticated={authenticated} />
    : <LiveIndexDetail market="KOSPI" name={ix.name} authenticated={authenticated} />;
  return <div className="h-full lg:overflow-y-auto pr-1">{detail}</div>;
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
            </div>
          ) : (
            <>
              <div className="flex items-center gap-3">
                {avatar}
                <div className="flex items-center flex-wrap gap-x-2 gap-y-1">
                  <span className="text-lg font-bold tracking-tight text-zinc-100">{name}</span>
                  {code && <span className="num text-xs text-zinc-500">{code}</span>}
                </div>
              </div>
              <div className="mt-1 flex items-baseline gap-2 flex-wrap">{priceGroup}</div>
            </>
          )}
        </div>
        {tab && setTab && (
          <div className="flex rounded-xl bg-zinc-800 p-0.5 text-xs shrink-0">
            {DETAIL_TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => setTab(t.key)}
                className={`px-3 py-1.5 rounded-lg transition-colors ${
                  tab === t.key ? "bg-elevated text-zinc-100 font-medium" : "text-zinc-500 hover:text-zinc-300"
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
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");

  if (priceQ.isLoading) return <Skeleton className="h-96 w-full" />;
  if (!priceQ.data) return <EmptyState message="지수를 불러오지 못했습니다" />;
  const value = priceQ.data.currentValue;
  const pct = priceQ.data.changeRate;

  return (
    <div className="flex flex-col gap-4">
      <DetailHeader name={name} price={value} pct={pct} priceInline decimal />
      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>지수 차트</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <LiveChart market={market} interval={chartInterval} />
      </div>
      {/* 코스피 수급만 로그인 없이 연다 — 대표 지수 하나로 이 화면이 뭘 주는지 보이게 한다.
          코스닥은 관문이다(auth/gate.py와 짝을 맞춘다). */}
      {authenticated || market === "KOSPI" ? (
        <>
          <RealSessionsCard market={market} date={date} />
          <RealInvestorTable market={market} />
        </>
      ) : (
        <SupplyGate what="시간대별 수급과 최근 5일 수급" />
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
    <div className="flex flex-col gap-4">
      <DetailHeader name={index.name} price={futValue} pct={futPct} priceInline decimal />

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

      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>선물 차트</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <FuturesChart market={market} interval={chartInterval} />
      </div>

      {authenticated ? (
        <>
          <FuturesSessionsCard market={market} date={todayStr()} />
          <FuturesDailyCard market={market} />
        </>
      ) : (
        <SupplyGate what="선물 시간대별 수급과 일별 수급" />
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

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="야간선물 시세를 불러오지 못했습니다" />;

  const up = data.gap >= 0;
  const tone = up ? "text-red-400" : "text-blue-400";
  const signed2 = (v: number) => `${v >= 0 ? "+" : "−"}${Math.abs(v).toFixed(2)}`;

  return (
    <div className="flex flex-col gap-4">
      <DetailHeader
        name={index.name}
       
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

/**
 * 매크로 상세 — 원달러 환율·WTI 유가·VIX를 한 화면에 세로로 쌓는다.
 * 환율은 24시간 돌지만 WTI는 CME 정산 휴식(06:00~07:00 KST)에, VIX는 미 정규장 밖에서 값이 멈춘다.
 */
function MacroDetail() {
  const { data, isLoading } = useMacroQuotes();

  if (isLoading) return <Skeleton className="h-96 w-full" />;
  if (!data) return <EmptyState message="매크로 지표를 불러오지 못했습니다" />;

  return (
    <div className="flex flex-col gap-8">
      <MacroSection
        title="원달러 환율"
        unit="원"
        target="USD_KRW"
        quote={data.usdKrw}
      />
      <MacroSection
        title="WTI 유가"
        unit="달러"
        target="WTI"
        quote={data.wti}
        delayed
      />
      <MacroSection title="VIX" unit="지수" target="VIX" quote={data.vix} />
    </div>
  );
}

/** 매크로 지표 한 덩어리 — 헤더 + 1분봉/일봉 토글 차트. */
function MacroSection({
  title,
  unit,
  target,
  quote,
  delayed,
}: {
  title: string;
  unit: string;
  target: MacroTarget;
  quote: MacroQuote | null;
  delayed?: boolean;
}) {
  const [chartInterval, setChartInterval] = useState<ChartInterval>("1m");

  return (
    <div className="flex flex-col gap-4">
      {quote === null ? (
        <EmptyState message={`${title} 시세를 불러오지 못했습니다`} />
      ) : (
        <DetailHeader
          name={title}
         
          price={quote.price}
          pct={quote.changeRate}
          chg={quote.priceChange}
          extra={delayed ? <DelayBadge /> : undefined}
          priceInline
          decimal
        />
      )}

      <div className="px-1">
        <div className="flex items-center justify-between mb-3">
          <span className={titleCls}>{title} 차트 · {unit}</span>
          <IntervalToggle value={chartInterval} onChange={setChartInterval} />
        </div>
        <MacroChart target={target} interval={chartInterval} />
      </div>
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
  if (isLoading) return <Skeleton className="h-[21.25rem] w-full" />;
  if (!items.length) return <EmptyState message="캔들 데이터가 없습니다" />;
  return (
    <CandleChart
      key={`macro-${target}-${interval}`}
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
 * 선물 세션별(오전/오후/마감) 순매수 — 당일 누적 스냅샷의 경계 diff(구간별 증분).
 * 폴러가 적재한 스냅샷이 있어야 하므로, 아직 없는 세션은 "집계 전".
 */
function FuturesSessionsCard({ market, date }: { market: MarketType; date: string }) {
  const { data, isLoading } = useFuturesInvestorSessions(market, date);
  const list = data?.sessions ?? [];
  // 공휴일이면 백엔드가 직전 거래일로 물러난다 — 실제 조회된 날짜로 라벨을 붙인다.
  const shownDate = data?.date ?? date;
  const edge = "border-l border-zinc-800"; // 기관상세 묶음 경계선
  const numCols = 3 + FUTURES_ORG_COLS.length + 1; // 개인·외국인·기관계 + 기관상세 + 기타법인

  return (
    <div className="px-1">
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>
          <span className="text-zinc-500 font-normal">{dayLabel(shownDate)}</span> 정규장 시간대별 수급
        </span>
        <span className="text-xs text-zinc-600">계약</span>
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
                <th colSpan={FUTURES_ORG_COLS.length} className={`text-center font-medium pb-1.5 text-zinc-400 border-b border-zinc-800 ${edge}`}>
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
                return (
                  <tr
                    key={s.name}
                    className={`[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-zinc-850`}
                  >
                    <td className="text-left py-2 pr-3">
                      <div className="text-[0.8rem] text-zinc-300">{s.name}</div>
                      <div className="text-[0.65rem] text-zinc-500 num">{s.time}</div>
                    </td>
                    {s.nets == null ? (
                      <td colSpan={numCols} className="text-right py-2 px-2.5 text-zinc-600">
                        집계 전
                      </td>
                    ) : (
                      <>
                        <td className="text-right py-2 px-2.5">
                          <FlowNum eok={s.nets.individual} delta={s.delta?.individual} />
                        </td>
                        <td className="text-right py-2 px-2.5">
                          <FlowNum eok={s.nets.foreign} delta={s.delta?.foreign} />
                        </td>
                        <td className="text-right py-2 pl-2.5 pr-5 font-medium">
                          <FlowNum eok={s.nets.institution} delta={s.delta?.institution} />
                        </td>
                        {FUTURES_ORG_COLS.map((c, i) => (
                          <td
                            key={c.key}
                            className={`text-right py-2 ${orgPad(i, FUTURES_ORG_COLS.length)} ${i === 0 ? edge : ""}`}
                          >
                            <FlowNum eok={s.nets!.breakdown[c.key]} delta={s.delta?.breakdown[c.key]} />
                          </td>
                        ))}
                        <td className={`text-right py-2 pl-5 pr-2.5 ${edge}`}>
                          <FlowNum eok={s.nets.otherCorp} delta={s.delta?.otherCorp} />
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
 * 선물 최근 5일 수급 — 거래일별 마지막 스냅샷(= 그날의 당일 누적).
 * 선물엔 일별 조회 API가 없어 폴러가 쌓은 스냅샷으로만 만든다. 적재 시작 전 과거는 소급되지 않는다.
 */
function FuturesDailyCard({ market }: { market: MarketType }) {
  const { data, isLoading } = useFuturesInvestorDaily(market, 5);
  const records = data ?? [];
  const edge = "border-l border-zinc-800"; // 기관상세 묶음 경계선

  return (
    <div className="px-1">
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>최근 5일 수급</span>
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
                <th colSpan={FUTURES_ORG_COLS.length} className={`text-center font-medium pb-1.5 text-zinc-400 border-b border-zinc-800 ${edge}`}>
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
                  className="[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-zinc-850"
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

/** 지수선물 1분봉/일봉 — KIS 근월물 캔들(OHLCV). */
function FuturesChart({ market, interval }: { market: MarketType; interval: ChartInterval }) {
  const { data, isLoading } = useFuturesCandles(market, interval);
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
      key={`futures-${market}-${interval}`}
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

/** "2026-07-10" → "26년 7월 10일" */
function fmtDay(iso: string) {
  const [y, m, d] = iso.split("-");
  return `${y.slice(2)}년 ${Number(m)}월 ${Number(d)}일`;
}

/** 시장 최근 5일 수급 — 키움 ka10051. 일자별 1회씩 호출하므로 유량 제한(초당 5건) 안에서 5일로 제한. */
function RealInvestorTable({ market }: { market: MarketType }) {
  const { data, isLoading } = useMarketInvestorDaily(market, 5);
  return <InvestorDailyTable records={data ?? []} isLoading={isLoading} days={5} />;
}

/** 최근 N일 수급 표 — 한 행에 개인·외국인·기관계 + 기관상세 7 + 기타법인. 시장·종목 공용. */
function InvestorDailyTable({
  records,
  isLoading,
  unit = "억원",
  days = 10,
}: {
  records: MarketInvestorDay[];
  isLoading: boolean;
  unit?: string;
  days?: number;
}) {
  const edge = "border-l border-zinc-800"; // 기관상세 묶음 경계선

  return (
    <div className="px-1">
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>최근 {days}일 수급</span>
        <span className="text-xs text-zinc-600">순매수 · {unit}</span>
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
                <th colSpan={ORG_COLS.length} className={`text-center font-medium pb-1.5 text-zinc-400 border-b border-zinc-800 ${edge}`}>
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
                  className="[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-zinc-850"
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

/** 세션별(오전/오후/마감) 순매수 — 당일 누적 스냅샷 경계 diff(구간별 증분). 스냅샷이 아직 없는 세션은 "집계 전". */
/** 조회 기준일 라벨. 주말엔 직전 평일을 보게 되므로 언제 것인지 밝힌다. */
function dayLabel(date: string): string {
  if (date === todayStr()) return "오늘";
  const [y, m, d] = date.split("-").map(Number);
  const dow = "일월화수목금토"[new Date(y, m - 1, d).getDay()];
  return `${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}(${dow})`;
}

function RealSessionsCard({ market, date }: { market: MarketType; date: string }) {
  const { data, isLoading } = useMarketInvestorSessions(market, date);
  const list = data?.sessions ?? [];
  // 공휴일이면 백엔드가 직전 거래일로 물러난다 — 실제 조회된 날짜로 라벨을 붙인다.
  const shownDate = data?.date ?? date;
  const edge = "border-l border-zinc-800"; // 기관상세 묶음 경계선
  const numCols = 3 + ORG_COLS.length + 1; // 개인·외국인·기관계 + 기관상세 + 기타법인

  return (
    <div className="px-1">
      <div className="flex items-baseline justify-between mb-3">
        <span className={titleCls}>
          <span className="text-zinc-500 font-normal">{dayLabel(shownDate)}</span> 시간대별 수급
        </span>
        <span className="text-xs text-zinc-600">억원</span>
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
                <th colSpan={ORG_COLS.length} className={`text-center font-medium pb-1.5 text-zinc-400 border-b border-zinc-800 ${edge}`}>
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
                return (
                  <tr
                    key={s.name}
                    className={`[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-zinc-850`}
                  >
                    <td className="text-left py-2 pr-3">
                      <div className="text-[0.8rem] text-zinc-300">{s.name}</div>
                      <div className="text-[0.65rem] text-zinc-500 num">{s.time}</div>
                    </td>
                    {s.nets == null ? (
                      <td colSpan={numCols} className="text-right py-2 px-2.5 text-zinc-600">
                        집계 전
                      </td>
                    ) : (
                      <>
                        <td className="text-right py-2 px-2.5">
                          <FlowNum eok={s.nets.individual} delta={s.delta?.individual} />
                        </td>
                        <td className="text-right py-2 px-2.5">
                          <FlowNum eok={s.nets.foreign} delta={s.delta?.foreign} />
                        </td>
                        <td className="text-right py-2 pl-2.5 pr-5 font-medium">
                          <FlowNum eok={s.nets.institution} delta={s.delta?.institution} />
                        </td>
                        {ORG_COLS.map((c, i) => (
                          <td key={c.key} className={`text-right py-2 ${orgPad(i, ORG_COLS.length)} ${i === 0 ? edge : ""}`}>
                            <FlowNum eok={s.nets!.breakdown[c.key]} delta={s.delta?.breakdown[c.key]} />
                          </td>
                        ))}
                        <td className={`text-right py-2 pl-5 pr-2.5 ${edge}`}>
                          <FlowNum eok={s.nets.otherCorp} delta={s.delta?.otherCorp} />
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
