import { useState } from "react";
import {
  useIndexMinuteCandles,
  useMarketInvestorNetBuy,
} from "../../api/queries";
import type { MarketInvestorNetBuyItem, MarketType } from "../../types";
import { formatEok, formatPct } from "../../lib/format";
import IndexLineChart from "./IndexLineChart";
import ProfitText from "./ProfitText";
import EmptyState from "./EmptyState";
import Skeleton from "./Skeleton";

const MARKET_LABEL: Record<MarketType, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };

/** 지수값 → 천 단위 쉼표 + 소수 2자리. */
const fmtIndex = (v: number) =>
  v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

type DetailTab = "detail" | "minute";

const DETAIL_TABS: { key: DetailTab; label: string }[] = [
  { key: "detail", label: "상세" },
  { key: "minute", label: "1분봉" },
];

/** 지수(코스피/코스닥) 상세/1분봉 패널 — 실시간 로그에서 지수 행 선택 시 우측에. */
export default function IndexDetailPanel({
  market,
  date,
  changeRate,
}: {
  market: MarketType | null;
  date: string;
  changeRate?: number | null;
}) {
  const [tab, setTab] = useState<DetailTab>("detail");
  const candlesQ = useIndexMinuteCandles(tab === "minute" ? market : null, date);
  const netBuyQ = useMarketInvestorNetBuy(tab === "detail" && market !== null);
  const candles = candlesQ.data ?? [];
  const lastValue = candles.length > 0 ? candles[candles.length - 1].close : null;

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
          {/* 상세/차트 토글 */}
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
      </header>

      <div className="pt-4">
        {tab === "minute" ? (
          candlesQ.isLoading ? (
            <Skeleton className="h-[28rem] w-full" />
          ) : candles.length === 0 ? (
            <div className="h-[28rem] flex items-center justify-center">
              <EmptyState message="장중에 지수 분봉이 표시됩니다" />
            </div>
          ) : (
            <IndexLineChart items={candles} changeRate={changeRate} className="w-full h-[28rem]" />
          )
        ) : netBuyQ.isLoading ? (
          <Skeleton className="h-[28rem] w-full" />
        ) : !netBuyQ.data || netBuyQ.data.length === 0 ? (
          <div className="h-[28rem] flex items-center justify-center">
            <EmptyState message="장중에 투자자 순매수가 표시됩니다" />
          </div>
        ) : (
          <NetBuyDetail items={netBuyQ.data} />
        )}
      </div>
    </div>
  );
}

// ============================================================
// 상세 — 코스피·코스닥 투자자 순매수
// ============================================================

function NetBuyDetail({ items }: { items: MarketInvestorNetBuyItem[] }) {
  // 코스피 먼저
  const sorted = [...items].sort((a) => (a.market === "KOSPI" ? -1 : 1));
  return (
    <div className="p-1 sm:p-3 space-y-4">
      <p className="text-xs text-zinc-500">당일 누적 투자자 순매수</p>
      {sorted.map((m) => (
        <div
          key={m.market}
          className="bg-zinc-900 rounded-2xl p-4"
        >
          <div className="flex items-baseline justify-between mb-3">
            <span className="text-sm font-semibold text-zinc-100">{MARKET_LABEL[m.market]}</span>
            <span className="text-xs text-zinc-400 num">
              {fmtIndex(m.indexValue)}
              <ProfitText value={m.changeRate / 100} format={formatPct} className="num ml-1.5" />
            </span>
          </div>
          <div className="grid grid-cols-3 gap-2">
            <NetBuyCell label="개인" eok={m.individualEok} />
            <NetBuyCell label="외인" eok={m.foreignEok} />
            <NetBuyCell label="기관" eok={m.institutionEok} />
          </div>
        </div>
      ))}
    </div>
  );
}

function NetBuyCell({ label, eok }: { label: string; eok: number }) {
  // 한국 거래소 관행 — 순매수(양수) 빨강 / 순매도(음수) 파랑
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
