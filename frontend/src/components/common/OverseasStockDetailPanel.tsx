import { useState, type ReactNode } from "react";
import {
  useOverseasStockDetail,
  useOverseasMinuteCandles,
  useOverseasDailyCandles,
} from "../../api/queries";
import type { OverseasStockDetailResponse, OverseasStockRankItem } from "../../types";
import { colorByPnL, formatPct, formatUsd } from "../../lib/format";
import Skeleton from "./Skeleton";
import CandleChart, { dailySeries, minuteSeries } from "./CandleChart";
import NumUsd from "./NumUsd";
import { CHART_H, ChartCard, ChartEmpty, Chevron, LeadingConditions, Segmented } from "./detailParts";

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
 * 해외 종목 상세 — 국내 상세와 같은 짜임(머리 / 주도주 체크리스트 / 차트). 해외는 투자자별 수급이 없다.
 * [chartOnly]면 체크리스트 없이 차트만 둔다.
 * [onBack]을 주면 모바일에서 맨 위에 "목록" 버튼을 단다.
 */
export default function OverseasStockDetailPanel({
  exchange,
  symbol,
  preview,
  chartOnly = false,
  onBack,
  insight,
}: {
  exchange: string | null;
  symbol: string | null;
  /** 주도주 목록에서 이미 받은 시세 — 상세 API를 기다리지 않고 머리를 그린다. */
  preview?: OverseasStockRankItem;
  chartOnly?: boolean;
  onBack?: () => void;
  /** 머리 바로 아래에 끼울 것 — 주도주 화면의 "왜 올랐나요?" 카드(026) */
  insight?: ReactNode;
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
  const quote = preview?.exchange === exchange && preview.symbol === symbol ? preview : d;

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

      {!quote && detailQ.isLoading ? (
        <Skeleton className="h-28 w-full" />
      ) : !quote ? (
        <p className="text-xs text-rose-700">상세 정보를 불러올 수 없습니다</p>
      ) : (
        <DetailHeader quote={quote} />
      )}

      {quote && detailQ.isError && (
        <p className="text-xs text-rose-700">주도주 체크리스트를 불러올 수 없습니다</p>
      )}

      {insight}

      {!chartOnly && d && <LeadingConditions results={d.filterResults} />}

      <ChartCard
        title={interval === "1m" ? "1분봉" : "일봉"}
        action={
          <Segmented
            label="차트 주기"
            items={[
              ["1m", "1분봉"],
              ["1d", "일봉"],
            ]}
            value={interval}
            onChange={setChartInterval}
          />
        }
      >
        {interval === "1m" ? (
          minuteQ.isLoading ? (
            <Skeleton className={`${CHART_H} w-full`} />
          ) : minutes.length === 0 ? (
            <ChartEmpty>분봉 데이터가 없습니다</ChartEmpty>
          ) : (
            <CandleChart
              key={`${symbol}-m`}
              series={minuteSeries(minutes)}
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
      </ChartCard>
    </div>
  );
}

/** 부호 붙인 달러 — 음수는 하이픈이 아니라 마이너스 기호. */
const signedUsd = (v: number) => `${v > 0 ? "+" : v < 0 ? "−" : ""}${formatUsd(Math.abs(v))}`;

/** 심볼·이름·거래소 / 큰 가격·전일 대비·등락률 / 통합 순위·거래대금. */
function DetailHeader({ quote }: { quote: OverseasStockRankItem | OverseasStockDetailResponse }) {
  const pct = quote.rate;
  const pctBadge = pct > 0 ? "bg-red-500/10" : pct < 0 ? "bg-blue-500/10" : "bg-zinc-800";
  const facts: [string, ReactNode][] = [
    ["통합 순위", `${quote.rank}위`],
    ["거래대금", `$${Math.round(quote.tradingValue).toLocaleString("en-US")}`],
  ];
  return (
    <header className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <h2 className="text-lg font-bold tracking-tight text-zinc-100">{quote.symbol}</h2>
        <span className="truncate text-xs text-zinc-500">
          {quote.name} · {exchangeLabel(quote.exchange)}
        </span>
      </div>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <NumUsd value={quote.price} className="num text-2xl font-bold tracking-tight text-zinc-100 sm:text-3xl" />
        <span className={`num text-base font-semibold ${colorByPnL(quote.diff)}`}>{signedUsd(quote.diff)}</span>
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
