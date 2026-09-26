import { useState, type ReactNode } from "react";
import {
  useOverseasStockDetail,
  useOverseasMinuteCandles,
  useOverseasDailyCandles,
} from "../../api/queries";
import type { OverseasStockDetailResponse } from "../../types";
import { colorByPnL, formatPct, formatUsd } from "../../lib/format";
import Skeleton from "./Skeleton";
import CandleChart, { dailySeries, minuteSeries } from "./CandleChart";
import NumUsd from "./NumUsd";
import { CHART_H, ChartEmpty, Chevron, LeadingConditions, Segmented } from "./detailParts";

const EXCHANGE_LABEL: Record<string, string> = {
  NAS: "나스닥",
  NYS: "뉴욕",
  AMS: "아멕스",
};
export function exchangeLabel(code: string): string {
  return EXCHANGE_LABEL[code] ?? code;
}

type ChartInterval = "1m" | "1d";

/**
 * 해외 종목 상세 — 국내 상세와 같은 짜임(머리 / 차트 / 주도주 조건). 해외는 투자자별 수급이 없다.
 * [chartOnly]면 주도주 조건 없이 차트만 둔다.
 * [onBack]을 주면 모바일에서 맨 위에 "목록" 버튼을 단다.
 */
export default function OverseasStockDetailPanel({
  exchange,
  symbol,
  chartOnly = false,
  onBack,
}: {
  exchange: string | null;
  symbol: string | null;
  chartOnly?: boolean;
  onBack?: () => void;
}) {
  const [interval, setChartInterval] = useState<ChartInterval>("1m");
  const detailQ = useOverseasStockDetail(exchange, symbol);
  const minuteQ = useOverseasMinuteCandles(interval === "1m" ? exchange : null, interval === "1m" ? symbol : null);
  const dailyQ = useOverseasDailyCandles(interval === "1d" ? exchange : null, interval === "1d" ? symbol : null);
  const d = detailQ.data;

  if (!exchange || !symbol) {
    return (
      <div className="flex h-[28rem] items-center justify-center text-sm text-zinc-600">
        종목을 선택하면 표시됩니다
      </div>
    );
  }

  const minutes = minuteQ.data ?? [];

  return (
    <div className="flex flex-col gap-6">
      {onBack && (
        <button
          type="button"
          onClick={onBack}
          className="-ml-1 -mb-2 flex w-fit items-center gap-0.5 text-sm text-zinc-400 hover:text-zinc-200 lg:hidden"
        >
          <Chevron dir="left" />
          목록
        </button>
      )}

      {!d ? <Skeleton className="h-28 w-full" /> : <DetailHeader detail={d} />}

      <section className="rounded-2xl bg-zinc-900 p-3 sm:p-4">
        <div className="mb-2 flex items-center justify-between gap-2 px-1">
          <h3 className="text-sm font-semibold text-zinc-400">
            {interval === "1m" ? "1분봉" : "일봉"}
          </h3>
          <Segmented
            label="차트 주기"
            items={[
              ["1m", "1분봉"],
              ["1d", "일봉"],
            ]}
            value={interval}
            onChange={setChartInterval}
          />
        </div>
        {interval === "1m" ? (
          minuteQ.isLoading ? (
            <Skeleton className={`${CHART_H} w-full`} />
          ) : minutes.length === 0 ? (
            <ChartEmpty>분봉 데이터가 없습니다</ChartEmpty>
          ) : (
            <CandleChart
              key={`${symbol}-m`}
              series={minuteSeries(minutes)}
              priceLines={[
                { price: Math.max(...minutes.map((c) => c.high)), title: "저항선", color: "#fb923c" },
                { price: Math.min(...minutes.map((c) => c.low)), title: "지지선", color: "#38bdf8" },
              ]}
              priceDecimals={2}
              className={`w-full ${CHART_H}`}
            />
          )
        ) : dailyQ.isLoading ? (
          <Skeleton className={`${CHART_H} w-full`} />
        ) : !dailyQ.data || dailyQ.data.length === 0 ? (
          <ChartEmpty>일봉 데이터가 없습니다</ChartEmpty>
        ) : (
          <CandleChart
            key={`${symbol}-d`}
            series={dailySeries(dailyQ.data)}
            timeVisible={false}
            priceDecimals={2}
            className={`w-full ${CHART_H}`}
          />
        )}
      </section>

      {!chartOnly && d && <LeadingConditions results={d.filterResults} />}
    </div>
  );
}

/** 부호 붙인 달러 — 음수는 하이픈이 아니라 마이너스 기호. */
const signedUsd = (v: number) => `${v > 0 ? "+" : v < 0 ? "−" : ""}${formatUsd(Math.abs(v))}`;

/** 심볼·이름·거래소 / 큰 가격·전일 대비·등락률 / 통합 순위·거래대금. */
function DetailHeader({ detail }: { detail: OverseasStockDetailResponse }) {
  const pct = detail.rate;
  const pctBadge = pct > 0 ? "bg-red-500/10" : pct < 0 ? "bg-blue-500/10" : "bg-zinc-800";
  const facts: [string, ReactNode][] = [
    ["통합 순위", `${detail.rank}위`],
    ["거래대금", `$${Math.round(detail.tradingValue).toLocaleString("en-US")}`],
  ];
  return (
    <header className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h2 className="text-lg font-bold tracking-tight text-zinc-100">{detail.symbol}</h2>
        <span className="truncate text-xs text-zinc-500">
          {detail.name} · {exchangeLabel(detail.exchange)}
        </span>
      </div>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <NumUsd value={detail.price} className="num text-2xl font-bold tracking-tight text-zinc-100 sm:text-3xl" />
        <span className={`num text-base font-semibold ${colorByPnL(detail.diff)}`}>{signedUsd(detail.diff)}</span>
        <span className={`num rounded-md px-2 py-0.5 text-[13px] font-bold ${pctBadge} ${colorByPnL(pct)}`}>
          {formatPct(pct / 100)}
        </span>
      </div>
      <p className="flex flex-wrap items-baseline gap-x-2 gap-y-1 text-[13px]">
        {facts.flatMap(([label, value], i) => [
          ...(i > 0 ? [<span key={`dot${i}`} aria-hidden className="text-zinc-700">·</span>] : []),
          <span key={label} className="flex items-baseline gap-1.5">
            <span className="text-zinc-500">{label}</span>
            <span className="num font-semibold text-zinc-300">{value}</span>
          </span>,
        ])}
      </p>
    </header>
  );
}
