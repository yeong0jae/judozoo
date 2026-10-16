import { useMemo, useState } from "react";
import {
  useMarketCandles,
  useMarketInvestorNetBuy,
  useMarketInvestorNetBuyAt,
} from "../../api/queries";
import type { MarketInvestorNetBuyItem, MarketType } from "../../types";
import { formatEok, formatPct } from "../../lib/format";
import CandleChart from "./CandleChart";
import { marketDailySeries, marketMinuteSeries } from "./tossCandles";
import ProfitText from "./ProfitText";
import EmptyState from "./EmptyState";
import Skeleton from "./Skeleton";

const MARKET_LABEL: Record<MarketType, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };

const fmtIndex = (v: number) =>
  v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

type DetailTab = "detail" | "minute" | "daily";

const DETAIL_TABS: { key: DetailTab; label: string }[] = [
  { key: "detail", label: "상세" },
  { key: "minute", label: "1분봉" },
  { key: "daily", label: "일봉" },
];

export default function IndexDetailPanel({
  market,
  changeRate,
  at = null,
}: {
  market: MarketType | null;
  changeRate?: number | null;
  at?: string | null;
}) {
  const [tab, setTab] = useState<DetailTab>("detail");
  const chartInterval = tab === "daily" ? "1d" : "1m";
  const candlesQ = useMarketCandles(tab !== "detail" ? market : null, chartInterval);
  const chartItems = candlesQ.data ?? [];
  const chartSeries = useMemo(
    () => (chartInterval === "1d" ? marketDailySeries(chartItems) : marketMinuteSeries(chartItems)),
    [chartItems, chartInterval],
  );
  const detailOn = tab === "detail" && market !== null;
  const netBuyLiveQ = useMarketInvestorNetBuy(detailOn);
  const netBuyAtQ = useMarketInvestorNetBuyAt(detailOn ? at : null, detailOn);
  const snapshot = at != null && netBuyAtQ.data && netBuyAtQ.data.length > 0 ? netBuyAtQ.data : null;
  const netBuyItems = snapshot ?? netBuyLiveQ.data ?? [];
  const netBuyLoading = (at != null && netBuyAtQ.isLoading) || (snapshot == null && netBuyLiveQ.isLoading);
  const netBuyTitle =
    snapshot != null ? `${at!.slice(11, 16)} 시점 투자자 순매수` : "당일 누적 투자자 순매수";
  const lastValue = chartItems.length > 0 ? chartItems[chartItems.length - 1].close : null;

  if (!market) {
    return (
      <div className="h-[28rem] flex items-center justify-center text-sm text-zinc-600">
        행을 선택하면 표시됩니다
      </div>
    );
  }

  return (
    <div className="flex flex-col">
      <header className="pb-4 border-b border-zinc-800">
        <div className="flex items-start justify-between gap-2">
          <div>
            <span className="text-lg font-bold tracking-tight text-zinc-100">{MARKET_LABEL[market]} 지수</span>
            {lastValue != null && (
              <div className="mt-1 flex items-baseline gap-2">
                <span className="num text-xl font-bold text-zinc-100">{fmtIndex(lastValue)}</span>
                {changeRate != null && (
                  <ProfitText value={changeRate / 100} format={formatPct} className="num text-sm font-semibold" />
                )}
              </div>
            )}
          </div>
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
        </div>
      </header>

      <div className="pt-4">
        {tab !== "detail" ? (
          candlesQ.isLoading ? (
            <Skeleton className="h-[28rem] w-full" />
          ) : chartItems.length === 0 ? (
            <div className="h-[28rem] flex items-center justify-center">
              <EmptyState message={tab === "minute" ? "장중에 지수 분봉이 표시됩니다" : "일봉 데이터가 없습니다"} />
            </div>
          ) : (
            <CandleChart
              key={`${market}-${chartInterval}`}
              series={chartSeries}
              timeVisible={chartInterval === "1m"}
              priceDecimals={2}
              className="w-full h-[28rem]"
            />
          )
        ) : netBuyLoading ? (
          <Skeleton className="h-[28rem] w-full" />
        ) : netBuyItems.length === 0 ? (
          <div className="h-[28rem] flex items-center justify-center">
            <EmptyState message="장중에 투자자 순매수가 표시됩니다" />
          </div>
        ) : (
          <NetBuyDetail items={netBuyItems} title={netBuyTitle} />
        )}
      </div>
    </div>
  );
}

function NetBuyDetail({ items, title }: { items: MarketInvestorNetBuyItem[]; title: string }) {
  const sorted = [...items].sort((a) => (a.market === "KOSPI" ? -1 : 1));
  return (
    <div className="p-1 sm:p-3 space-y-4">
      <p className="text-xs text-zinc-500">{title}</p>
      {sorted.map((m) => (
        <div
          key={m.market}
          className="bg-zinc-900 rounded-2xl p-4"
        >
          <div className="flex items-baseline justify-between mb-3">
            <span className="text-sm font-semibold text-zinc-100">{MARKET_LABEL[m.market]}</span>
            <span className="text-xs text-zinc-400 num">
              {m.indexValue === null ? "—" : fmtIndex(m.indexValue)}
              {m.changeRate !== null && (
                <ProfitText value={m.changeRate / 100} format={formatPct} className="num ml-1.5" />
              )}
            </span>
          </div>
          <div className="grid grid-cols-4 gap-2">
            <NetBuyCell label="개인" eok={m.individualEok} />
            <NetBuyCell label="외인" eok={m.foreignEok} />
            <NetBuyCell label="기관" eok={m.institutionEok} />
            <NetBuyCell label="기타법인" eok={m.otherCorpEok} />
          </div>
          <div className="mt-2.5">
            <div className="text-[11px] text-zinc-600 mb-1">기관 세부</div>
            <div className="grid grid-cols-6 gap-1.5">
              {ORG_DETAIL.map(({ key, label }) => (
                <OrgCell key={key} label={label} eok={m[key] ?? 0} />
              ))}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}

type OrgKey =
  | "pensionFundEok"
  | "trustEok"
  | "financialInvestmentEok"
  | "privateEquityEok"
  | "insuranceEok"
  | "bankEok";
const ORG_DETAIL: { key: OrgKey; label: string }[] = [
  { key: "pensionFundEok", label: "연기금" },
  { key: "trustEok", label: "투신" },
  { key: "financialInvestmentEok", label: "금융투자" },
  { key: "privateEquityEok", label: "사모" },
  { key: "insuranceEok", label: "보험" },
  { key: "bankEok", label: "은행" },
];

function OrgCell({ label, eok }: { label: string; eok: number }) {
  const tone = eok > 0 ? "text-red-400" : eok < 0 ? "text-blue-400" : "text-zinc-500";
  const sign = eok > 0 ? "+" : eok < 0 ? "-" : "";
  return (
    <div className="rounded-md bg-elevated px-1.5 py-1 text-center">
      <div className="text-[11px] text-zinc-500">{label}</div>
      <div className={`num text-xs font-medium mt-0.5 ${tone}`}>
        {sign}
        {formatEok(Math.abs(eok))}
      </div>
    </div>
  );
}

function NetBuyCell({ label, eok }: { label: string; eok: number }) {
  const tone = eok > 0 ? "text-red-400" : eok < 0 ? "text-blue-400" : "text-zinc-500";
  const sign = eok > 0 ? "+" : eok < 0 ? "-" : "";
  return (
    <div className="text-center">
      <div className="text-xs text-zinc-500 mb-1">{label}</div>
      <div className={`num text-sm font-semibold ${tone}`}>
        {sign}
        {formatEok(Math.abs(eok))}
      </div>
    </div>
  );
}
