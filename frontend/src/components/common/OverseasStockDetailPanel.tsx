import { useState } from "react";
import {
  useOverseasStockDetail,
  useOverseasMinuteCandles,
  useOverseasDailyCandles,
} from "../../api/queries";
import type {
  OverseasFilterResult,
  OverseasSwingHighSignal,
} from "../../types";
import { formatPct, formatUsd } from "../../lib/format";
import ProfitText from "./ProfitText";
import Skeleton from "./Skeleton";
import StockAvatar from "./StockAvatar";
import CandleChart, { dailySeries, minuteSeries } from "./CandleChart";

const EXCHANGE_LABEL: Record<string, string> = {
  NAS: "나스닥",
  NYS: "뉴욕",
  AMS: "아멕스",
};
export function exchangeLabel(code: string): string {
  return EXCHANGE_LABEL[code] ?? code;
}

type DetailTab = "detail" | "minute" | "daily";
const DETAIL_TABS: { key: DetailTab; label: string }[] = [
  { key: "detail", label: "상세" },
  { key: "minute", label: "1분봉" },
  { key: "daily", label: "일봉" },
];

/** 해외 종목 상세/차트 패널 — exchange/symbol만 받아 detail로 완결. 후보 조회·실시간 로그 공용. */
export default function OverseasStockDetailPanel({
  exchange,
  symbol,
}: {
  exchange: string | null;
  symbol: string | null;
}) {
  const [tab, setTab] = useState<DetailTab>("detail");
  const detailQ = useOverseasStockDetail(exchange, symbol);
  const minuteQ = useOverseasMinuteCandles(tab === "minute" ? exchange : null, tab === "minute" ? symbol : null);
  const dailyQ = useOverseasDailyCandles(tab === "daily" ? exchange : null, tab === "daily" ? symbol : null);
  const CH = "h-[28rem]";
  const d = detailQ.data;

  if (!exchange || !symbol) {
    return (
      <div className="h-[28rem] flex items-center justify-center text-sm text-zinc-600">
        종목을 선택하면 표시됩니다
      </div>
    );
  }

  return (
    <div className="flex flex-col lg:max-h-[calc(100vh-8rem)]">
      <header className="pb-4 border-b border-zinc-800">
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-center gap-3 min-w-0">
            <StockAvatar name={symbol} code={symbol} size={40} />
            <div className="min-w-0">
              <div className="flex items-center flex-wrap gap-x-2">
                <span className="text-lg font-bold tracking-tight text-zinc-100">{symbol}</span>
                <span className="text-xs text-zinc-500">{exchangeLabel(exchange)}</span>
              </div>
              <div className="text-xs text-zinc-400 truncate">{d?.ename || d?.name || ""}</div>
            </div>
          </div>
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
        </div>
        {d && (
          <div className="mt-3 flex items-baseline gap-2">
            <span className="text-2xl font-bold num text-zinc-100 tracking-tight">${formatUsd(d.price)}</span>
            <ProfitText value={d.rate / 100} format={formatPct} className="num text-sm font-semibold" />
          </div>
        )}
      </header>

      <div className="flex-1 lg:overflow-y-auto pt-5">
        {tab === "detail" ? (
          !d ? (
            <div className="space-y-2">
              {Array.from({ length: 5 }).map((_, i) => (
                <Skeleton key={i} className="h-12 w-full" />
              ))}
            </div>
          ) : (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
              <FilterResultsList results={d.filterResults} />
              <div className="space-y-6">
                <dl className="grid grid-cols-2 gap-x-6 gap-y-4 text-sm">
                  <Field label="통합 순위" value={`${d.rank}위`} />
                  <Field label="거래소" value={exchangeLabel(d.exchange)} />
                  <Field
                    label="전일 대비"
                    value={
                      <ProfitText
                        value={d.rate / 100}
                        format={() => `${d.diff >= 0 ? "+" : "-"}$${formatUsd(Math.abs(d.diff))}`}
                        className="num"
                      />
                    }
                  />
                  <Field label="거래대금" value={`$${Math.round(d.tradingValue).toLocaleString("en-US")}`} />
                  <Field label="종목명" value={d.name} span2 />
                </dl>
                {d.swingHighSignal && (
                  <BreakoutSignalSection signal={d.swingHighSignal} currentPrice={d.price} />
                )}
              </div>
            </div>
          )
        ) : tab === "minute" ? (
          minuteQ.isLoading ? (
            <Skeleton className={`${CH} w-full`} />
          ) : !minuteQ.data || minuteQ.data.length === 0 ? (
            <div className={`${CH} flex items-center justify-center text-xs text-zinc-600`}>
              분봉 데이터가 없습니다
            </div>
          ) : (
            <CandleChart
              key={`${symbol}-m`}
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
            key={`${symbol}-d`}
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

function Field({ label, value, span2 }: { label: string; value: React.ReactNode; span2?: boolean }) {
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
    <div className="space-y-1">
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm font-semibold text-zinc-400">필터</span>
        <span className="text-xs num text-zinc-400">
          <span className="text-emerald-400 font-semibold">{passedCount}</span> / {results.length} 통과
        </span>
      </div>
      {results.map((r) => (
        <div
          key={r.filterName}
          className="flex items-center gap-3 px-3 py-2.5 rounded-xl bg-zinc-900"
        >
          <div className="flex-1 min-w-0">
            <div className="text-xs font-medium text-zinc-200">{r.filterName}</div>
            <div className="text-xs text-zinc-500 mt-0.5">{r.criteriaDescription}</div>
          </div>
          <div className="num text-xs text-zinc-300 shrink-0">{r.actualValue}</div>
          <span
            className={`text-[10px] px-1.5 py-0.5 rounded-md shrink-0 ${
              r.passed ? "bg-emerald-400/10 text-emerald-400" : "bg-zinc-500/15 text-zinc-400"
            }`}
          >
            {r.passed ? "통과" : "미달"}
          </span>
        </div>
      ))}
    </div>
  );
}

function formatElapsed(ms: number): string {
  const min = Math.floor(ms / 60000);
  if (min <= 0) return "방금";
  if (min < 60) return `${min}분 전`;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return m === 0 ? `${h}시간 전` : `${h}시간 ${m}분 전`;
}

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
  const peakTime = peakDate.toTimeString().slice(0, 5);
  const elapsed = formatElapsed(Date.now() - peakDate.getTime());
  const gap = signal.peakPrice - currentPrice;
  const status = breakoutStatus(signal.gapRate);

  return (
    <section>
      <div className="flex items-baseline justify-between mb-3">
        <span className="text-sm font-semibold text-zinc-400">주도주 돌파 매매 시그널</span>
        <span className="text-xs text-zinc-600">최근 2거래일 고가</span>
      </div>
      <div className="bg-zinc-900 rounded-2xl p-4">
        <div className="flex items-baseline justify-between">
          <span className="text-xs text-zinc-400">돌파선</span>
          <span className="flex items-baseline gap-2">
            <span className="num text-sm font-semibold text-zinc-100">${formatUsd(signal.peakPrice)}</span>
            <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${status.chip}`}>{status.label}</span>
          </span>
        </div>
        <div className="mt-1 text-xs text-zinc-500 num">
          {peakDate.getDate()}일 {peakTime} 형성 · {elapsed}
        </div>
        <div className="my-3 h-px bg-zinc-800" />
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
