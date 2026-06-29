import { Fragment, useEffect, useState } from "react";
import {
  useOverseasRanking,
  useOverseasStockDetail,
  useOverseasMinuteCandles,
  useOverseasDailyCandles,
} from "../api/queries";
import type {
  OverseasFilterResult,
  OverseasStockRankItem,
  OverseasSwingHighSignal,
} from "../types";
import { formatPct } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import StockAvatar from "../components/common/StockAvatar";
import ChangeRateSelector, {
  CHANGE_RATE_OPTIONS,
} from "../components/common/ChangeRateSelector";
import CandleChart, {
  dailySeries,
  minuteSeries,
} from "../components/common/CandleChart";
import { useArrowStockNav } from "../lib/useArrowStockNav";

const MIN_CHANGE_RATE_KEY = "overseasStock.minChangeRate";

// 거래소 코드 → 한글 라벨
const EXCHANGE_LABEL: Record<string, string> = {
  NAS: "나스닥",
  NYS: "뉴욕",
  AMS: "아멕스",
};

function exchangeLabel(code: string): string {
  return EXCHANGE_LABEL[code] ?? code;
}

// 미국 주식 가격 — 소수 2자리 달러 표기
function formatUsd(value: number): string {
  return value.toLocaleString("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

export default function OverseasLeadingStocks({ toggle }: { toggle?: React.ReactNode }) {
  // 당일 등락률 임계값(%) — 국내와 동일하게 localStorage 보관, 기본 7%.
  const [minChangeRate, setMinChangeRate] = useState(() => {
    const raw = localStorage.getItem(MIN_CHANGE_RATE_KEY);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : 7;
  });
  useEffect(() => {
    localStorage.setItem(MIN_CHANGE_RATE_KEY, String(minChangeRate));
  }, [minChangeRate]);

  const { data, isLoading, isFetching } = useOverseasRanking(minChangeRate);
  const stocks = data ?? [];
  const [openSymbol, setOpenSymbol] = useState<string | null>(null);

  // ↑/↓ 방향키로 선택 종목 이동
  useArrowStockNav(
    stocks.map((s) => s.symbol),
    openSymbol,
    setOpenSymbol,
  );

  // 진입 시 첫 종목 기본 선택, 선택 종목이 리스트에서 사라지면 다시 첫 종목으로
  useEffect(() => {
    if (stocks.length === 0) return;
    if (openSymbol === null || !stocks.some((s) => s.symbol === openSymbol)) {
      setOpenSymbol(stocks[0].symbol);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stocks]);

  const selected = stocks.find((s) => s.symbol === openSymbol) ?? null;

  return (
    <div className="space-y-6">
      <Header totalCount={data?.length} loading={isFetching} />

      {toggle}

      {/* 종목 선택 시 좌(목록) / 우(상세) 2분할, 선택 없으면 목록 전체 폭 */}
      <div
        className={
          openSymbol
            ? "grid grid-cols-1 lg:grid-cols-[9fr_11fr] gap-6 items-start"
            : ""
        }
      >
        <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
          {/* 등락률 임계값 선택 — 리스트 우측 상단 */}
          <div className="flex justify-end px-4 py-2.5 border-b border-white/[0.04]">
            <ChangeRateSelector value={minChangeRate} onChange={setMinChangeRate} />
          </div>
          {isLoading ? (
            <div className="p-6 space-y-3">
              {Array.from({ length: 10 }).map((_, i) => (
                <Skeleton key={i} className="h-10 w-full" />
              ))}
            </div>
          ) : stocks.length === 0 ? (
            <EmptyState message="데이터가 없습니다" />
          ) : (
            <>
              <RankingTable
                stocks={stocks}
                selectedSymbol={openSymbol}
                onOpen={setOpenSymbol}
              />
              <RankingCards
                stocks={stocks}
                selectedSymbol={openSymbol}
                onOpen={setOpenSymbol}
              />
            </>
          )}
        </section>

        {openSymbol && (
          <aside className="lg:sticky lg:top-6">
            <StockDetailPanel stock={selected} />
          </aside>
        )}
      </div>
    </div>
  );
}

// ============================================================
// Header
// ============================================================

function Header({
  totalCount,
  loading,
}: {
  totalCount: number | undefined;
  loading: boolean;
}) {
  return (
    <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
      <div>
        <h2 className="text-xl font-bold flex items-center gap-2">
          주도주 후보
          <span
            className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
              loading ? "animate-ping" : "animate-pulse"
            }`}
            aria-label={loading ? "갱신 중" : "대기"}
          />
        </h2>
        <p className="text-xs text-zinc-500 mt-0.5">
          나스닥·뉴욕·아멕스 통합 거래대금 상위 + 당일 등락률 필터 통과 · 15초 자동 갱신
        </p>
      </div>
      {typeof totalCount === "number" && (
        <div className="text-xs text-zinc-300 font-medium">{totalCount}건</div>
      )}
    </div>
  );
}

// ============================================================
// Ranking table (데스크톱)
// ============================================================

function RankingTable({
  stocks,
  selectedSymbol,
  onOpen,
}: {
  stocks: OverseasStockRankItem[];
  selectedSymbol: string | null;
  onOpen: (symbol: string) => void;
}) {
  return (
    <table className="hidden md:table w-full text-xs">
      <thead className="text-zinc-500">
        <tr>
          <th className="pl-4 py-2.5 text-left font-medium w-10">순위</th>
          <th className="px-2 py-2.5 text-left font-medium">종목</th>
          <th className="px-4 py-2.5 text-right font-medium">현재가</th>
          <th className="px-4 py-2.5 text-right font-medium">등락률</th>
          <th className="px-4 py-2.5 text-right font-medium">거래대금(USD)</th>
        </tr>
      </thead>
      <tbody>
        {stocks.length > 0 && <GroupHeader label="거래대금 1, 2, 3위" />}
        {stocks.map((s, idx) => {
          const isSelected = selectedSymbol === s.symbol;
          return (
            <Fragment key={s.symbol}>
              {idx === 3 && <GroupHeader label="주도주 후보" />}
              <tr
                data-stock-code={s.symbol}
                className={`border-t border-white/[0.04] hover:bg-white/[0.03] cursor-pointer transition-colors ${
                  isSelected ? "bg-emerald-900" : ""
                }`}
                onClick={() => onOpen(s.symbol)}
              >
                <td className="pl-4 py-3.5 text-zinc-500 num w-10">{s.rank}</td>
                <td className="px-2 py-3.5">
                  <div className="flex items-center gap-3">
                    <StockAvatar name={s.symbol} code={s.symbol} />
                    <div className="min-w-0">
                      <div className="font-semibold text-zinc-100">{s.symbol}</div>
                      <div className="text-xs text-zinc-500 truncate max-w-[12rem]">{s.name}</div>
                    </div>
                  </div>
                </td>
                <td className="px-4 py-3.5 text-right num font-medium text-zinc-100">
                  {formatUsd(s.price)}
                </td>
                <td className="px-4 py-3.5 text-right num font-medium">
                  <ProfitText value={s.rate / 100} format={formatPct} />
                </td>
                <td className="px-4 py-3.5 text-right num text-zinc-400">
                  {Math.round(s.tradingValue).toLocaleString("en-US")}
                </td>
              </tr>
            </Fragment>
          );
        })}
      </tbody>
    </table>
  );
}

function GroupHeader({ label }: { label: string }) {
  return (
    <tr aria-hidden className="border-t border-white/[0.04] bg-white/[0.02]">
      <td colSpan={5} className="px-4 py-2.5">
        <span className="text-xs font-semibold text-zinc-400">{label}</span>
      </td>
    </tr>
  );
}

// ============================================================
// Ranking cards (모바일)
// ============================================================

function RankingCards({
  stocks,
  selectedSymbol,
  onOpen,
}: {
  stocks: OverseasStockRankItem[];
  selectedSymbol: string | null;
  onOpen: (symbol: string) => void;
}) {
  return (
    <div className="md:hidden">
      {stocks.map((s, idx) => {
        const isSelected = selectedSymbol === s.symbol;
        return (
          <Fragment key={s.symbol}>
            {idx === 0 && <CardGroupHeader label="거래대금 1, 2, 3위" />}
            {idx === 3 && <CardGroupHeader label="주도주 후보" />}
            <div
              data-stock-code={s.symbol}
              className={`border-t border-white/[0.04] px-4 py-3.5 flex flex-col gap-1 cursor-pointer ${
                isSelected ? "bg-emerald-900" : ""
              }`}
              onClick={() => onOpen(s.symbol)}
            >
              {/* 1행: 순위 · 아바타 · 심볼 · 현재가 */}
              <div className="flex items-center gap-2">
                <span className="text-zinc-500 text-xs num w-4 shrink-0">{s.rank}</span>
                <StockAvatar name={s.symbol} code={s.symbol} size={26} />
                <span className="font-semibold truncate flex-1 min-w-0">{s.symbol}</span>
                <span className="num shrink-0 font-medium text-zinc-100">{formatUsd(s.price)}</span>
              </div>
              {/* 2행: 종목명 · 등락률 */}
              <div className="flex items-center gap-2 pl-[3.25rem]">
                <span className="text-xs text-zinc-500 truncate flex-1 min-w-0">
                  {s.name}
                </span>
                <ProfitText
                  value={s.rate / 100}
                  format={formatPct}
                  className="num text-xs shrink-0"
                />
              </div>
            </div>
          </Fragment>
        );
      })}
    </div>
  );
}

function CardGroupHeader({ label }: { label: string }) {
  return (
    <div className="bg-zinc-950 border-t border-zinc-800 px-4 py-2.5">
      <span className="text-sm font-semibold text-zinc-200">{label}</span>
    </div>
  );
}

// ============================================================
// 상세 패널 — 선택 종목 기본 정보
// ============================================================

type DetailTab = "detail" | "minute" | "daily";

const DETAIL_TABS: { key: DetailTab; label: string }[] = [
  { key: "detail", label: "상세" },
  { key: "minute", label: "1분봉" },
  { key: "daily", label: "일봉" },
];

function StockDetailPanel({ stock }: { stock: OverseasStockRankItem | null }) {
  const [tab, setTab] = useState<DetailTab>("detail");
  const ex = stock?.exchange ?? null;
  const sym = stock?.symbol ?? null;
  const detailQ = useOverseasStockDetail(tab === "detail" ? ex : null, tab === "detail" ? sym : null);
  const minuteQ = useOverseasMinuteCandles(tab === "minute" ? ex : null, tab === "minute" ? sym : null);
  const dailyQ = useOverseasDailyCandles(tab === "daily" ? ex : null, tab === "daily" ? sym : null);
  const CH = "h-[28rem]";

  if (!stock) {
    return (
      <div className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
        <div className="h-[28rem] flex items-center justify-center text-sm text-zinc-600">
          종목을 선택하면 표시됩니다
        </div>
      </div>
    );
  }

  return (
    <div className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
      <header className="px-4 sm:px-6 py-4 border-b border-white/[0.04]">
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-center gap-3 min-w-0">
            <StockAvatar name={stock.symbol} code={stock.symbol} size={36} />
            <div className="min-w-0">
              <div className="flex items-center flex-wrap gap-x-2">
                <span className="text-base font-semibold">{stock.symbol}</span>
                <span className="text-xs text-zinc-500">{exchangeLabel(stock.exchange)}</span>
              </div>
              <div className="text-xs text-zinc-400 truncate">{stock.ename || stock.name}</div>
            </div>
          </div>
          {/* 상세/차트 토글 */}
          <div className="flex rounded-lg bg-white/[0.04] p-0.5 text-xs shrink-0">
            {DETAIL_TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => setTab(t.key)}
                className={`px-2.5 py-1 rounded-md transition-colors ${
                  tab === t.key ? "bg-white/[0.1] text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
                }`}
              >
                {t.label}
              </button>
            ))}
          </div>
        </div>
        <div className="mt-3 flex items-baseline gap-2">
          <span className="text-2xl font-bold num text-zinc-100">${formatUsd(stock.price)}</span>
          <ProfitText
            value={stock.rate / 100}
            format={formatPct}
            className="num text-sm font-medium"
          />
        </div>
      </header>

      <div className="p-4 sm:p-6">
        {tab === "detail" ? (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
            {detailQ.data ? (
              <FilterResultsList results={detailQ.data.filterResults} />
            ) : (
              <div className="space-y-2">
                {Array.from({ length: 3 }).map((_, i) => (
                  <Skeleton key={i} className="h-12 w-full" />
                ))}
              </div>
            )}
            <div className="space-y-6">
              <dl className="grid grid-cols-2 gap-x-6 gap-y-4 text-sm">
                <Field label="통합 순위" value={`${stock.rank}위`} />
                <Field label="거래소" value={exchangeLabel(stock.exchange)} />
                <Field
                  label="전일 대비"
                  value={
                    <ProfitText
                      value={stock.rate / 100}
                      format={() => `${stock.diff >= 0 ? "+" : "-"}$${formatUsd(Math.abs(stock.diff))}`}
                      className="num"
                    />
                  }
                />
                <Field label="거래대금" value={`$${Math.round(stock.tradingValue).toLocaleString("en-US")}`} />
                <Field label="종목명" value={stock.name} span2 />
              </dl>
              {detailQ.data?.swingHighSignal && (
                <BreakoutSignalSection
                  signal={detailQ.data.swingHighSignal}
                  currentPrice={stock.price}
                />
              )}
            </div>
          </div>
        ) : tab === "minute" ? (
          minuteQ.isLoading ? (
            <Skeleton className={`${CH} w-full`} />
          ) : !minuteQ.data || minuteQ.data.length === 0 ? (
            <div className={`${CH} flex items-center justify-center text-xs text-zinc-600`}>
              분봉 데이터가 없습니다
            </div>
          ) : (
            <CandleChart
              key={`${stock.symbol}-m`}
              series={minuteSeries(minuteQ.data)}
              priceLine={Math.max(...minuteQ.data.map((c) => c.high))}
              priceDecimals={2}
              className={`w-full ${CH}`}
            />
          )
        ) : dailyQ.isLoading ? (
          <Skeleton className={`${CH} w-full`} />
        ) : !dailyQ.data || dailyQ.data.length === 0 ? (
          <div className={`${CH} flex items-center justify-center text-xs text-zinc-600`}>
            일봉 데이터가 없습니다
          </div>
        ) : (
          <CandleChart
            key={`${stock.symbol}-d`}
            series={dailySeries(dailyQ.data)}
            timeVisible={false}
            priceDecimals={2}
            className={`w-full ${CH}`}
          />
        )}
      </div>
    </div>
  );
}

function Field({
  label,
  value,
  span2,
}: {
  label: string;
  value: React.ReactNode;
  span2?: boolean;
}) {
  return (
    <div className={span2 ? "col-span-2" : ""}>
      <dt className="text-xs text-zinc-500 mb-1">{label}</dt>
      <dd className="text-zinc-100 num font-medium">{value}</dd>
    </div>
  );
}

function FilterResultsList({ results }: { results: OverseasFilterResult[] }) {
  const passedCount = results.filter((r) => r.passed).length;
  return (
    <div className="space-y-2">
      <div className="text-xs text-zinc-500 mb-2">
        {passedCount} / {results.length}개 필터 통과
      </div>
      {results.map((r) => (
        <div
          key={r.filterName}
          className={`flex items-start gap-3 px-3 py-2.5 rounded border ${
            r.passed ? "bg-emerald-50 border-emerald-200" : "bg-rose-50 border-rose-200"
          }`}
        >
          <span className="text-xs mt-0.5">{r.passed ? "✓" : "✗"}</span>
          <div className="flex-1 min-w-0">
            <div className="text-xs font-medium">{r.filterName}</div>
            <div className="text-xs text-zinc-500 mt-0.5">기준: {r.criteriaDescription}</div>
          </div>
          <div className="text-xs num shrink-0">{r.actualValue}</div>
        </div>
      ))}
    </div>
  );
}

// 전고점 형성 후 경과 시간을 사람이 읽기 좋게
function formatElapsed(ms: number): string {
  const min = Math.floor(ms / 60000);
  if (min <= 0) return "방금";
  if (min < 60) return `${min}분 전`;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return m === 0 ? `${h}시간 전` : `${h}시간 ${m}분 전`;
}

// 잔여 상승률(%)로 돌파 임박도를 라벨/색으로 구분
function breakoutStatus(gapRate: number): { label: string; chip: string; gap: string } {
  if (gapRate <= 0)
    return { label: "돌파", chip: "bg-emerald-500/15 text-emerald-400", gap: "text-emerald-400" };
  if (gapRate < 2.0)
    return { label: "임박", chip: "bg-amber-500/20 text-amber-300", gap: "text-amber-300" };
  if (gapRate < 4.0)
    return { label: "주시", chip: "bg-amber-500/15 text-amber-400", gap: "text-amber-400" };
  return { label: "관망", chip: "bg-zinc-700/40 text-zinc-400", gap: "text-zinc-200" };
}

function BreakoutSignalSection({
  signal,
  currentPrice,
}: {
  signal: OverseasSwingHighSignal;
  currentPrice: number;
}) {
  const broke = signal.gapRate <= 0;
  const peakDate = new Date(signal.peakAt);
  const peakTime = peakDate.toTimeString().slice(0, 5); // HH:mm
  const elapsed = formatElapsed(Date.now() - peakDate.getTime());
  const gap = signal.peakPrice - currentPrice; // 돌파까지 더 올라야 하는 금액 (돌파 시 음수)
  const status = breakoutStatus(signal.gapRate);

  return (
    <section>
      <h3 className="text-xs font-semibold text-zinc-200 mb-3">
        주도주 돌파 매매 시그널
        <span className="ml-2 text-xs font-normal text-zinc-500">최근 2거래일 고가 기준</span>
      </h3>
      <div className="bg-zinc-950 border border-white/[0.04] rounded-xl p-4">
        {/* 돌파선(전고점) + 임박도 칩 */}
        <div className="flex items-baseline justify-between">
          <span className="text-xs text-zinc-400">돌파선</span>
          <span className="flex items-baseline gap-2">
            <span className="num text-sm font-semibold text-zinc-100">
              ${formatUsd(signal.peakPrice)}
            </span>
            <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${status.chip}`}>
              {status.label}
            </span>
          </span>
        </div>
        <div className="mt-1 text-xs text-zinc-500 num">
          {peakDate.getDate()}일 {peakTime} 형성 · {elapsed}
        </div>

        <div className="my-3 border-t border-zinc-800" />

        {/* 현재가 → 돌파까지 거리($/%) */}
        <div className="flex items-baseline justify-between">
          <span className="text-xs text-zinc-400">현재가</span>
          <span className="num text-sm text-zinc-300">${formatUsd(currentPrice)}</span>
        </div>
        <div className="mt-2 flex items-baseline justify-between">
          <span className="text-xs text-zinc-400">{broke ? "돌파" : "돌파까지"}</span>
          <span className={`num text-sm font-semibold ${status.gap}`}>
            {broke
              ? `$${formatUsd(-gap)} (+${(-signal.gapRate).toFixed(1)}%)`
              : `$${formatUsd(gap)} (${signal.gapRate.toFixed(1)}%)`}
          </span>
        </div>
      </div>
    </section>
  );
}
