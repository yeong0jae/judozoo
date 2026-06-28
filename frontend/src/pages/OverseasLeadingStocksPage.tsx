import { useState } from "react";
import { useOverseasRanking } from "../api/queries";
import type { OverseasExchange, OverseasStockRankItem } from "../types";
import { formatPct } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";

const EXCHANGES: { code: OverseasExchange; label: string }[] = [
  { code: "NAS", label: "나스닥" },
  { code: "NYS", label: "뉴욕" },
  { code: "AMS", label: "아멕스" },
];

export default function OverseasLeadingStocksPage() {
  const [excd, setExcd] = useState<OverseasExchange>("NAS");
  const { data, isLoading, isFetching } = useOverseasRanking(excd);

  return (
    <div className="space-y-6">
      <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
        <div>
          <h2 className="text-xl font-bold flex items-center gap-2">
            해외주식 거래대금 순위
            <span
              className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
                isFetching ? "animate-ping" : "animate-pulse"
              }`}
            />
          </h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            당일 거래대금 상위 30위 · 30초 자동 갱신
          </p>
        </div>

        {/* 거래소 탭 */}
        <div className="flex gap-1 bg-zinc-900 rounded-lg p-1 border border-white/[0.04]">
          {EXCHANGES.map((ex) => (
            <button
              key={ex.code}
              type="button"
              onClick={() => setExcd(ex.code)}
              className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                excd === ex.code
                  ? "bg-white/[0.08] text-zinc-100"
                  : "text-zinc-400 hover:text-zinc-200"
              }`}
            >
              {ex.label}
            </button>
          ))}
        </div>
      </div>

      <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
        {isLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 10 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : !data || data.length === 0 ? (
          <EmptyState message="데이터가 없습니다" />
        ) : (
          <RankingTable stocks={data} />
        )}
      </section>
    </div>
  );
}

function RankingTable({ stocks }: { stocks: OverseasStockRankItem[] }) {
  return (
    <table className="w-full text-xs">
      <thead className="text-zinc-500">
        <tr>
          <th className="pl-4 py-2.5 text-left font-medium w-10">순위</th>
          <th className="px-2 py-2.5 text-left font-medium">종목</th>
          <th className="px-4 py-2.5 text-right font-medium">현재가</th>
          <th className="px-4 py-2.5 text-right font-medium">등락률</th>
          <th className="px-4 py-2.5 text-right font-medium hidden sm:table-cell">거래대금(USD)</th>
        </tr>
      </thead>
      <tbody>
        {stocks.map((s) => (
          <tr
            key={s.symbol}
            className="border-t border-white/[0.04] hover:bg-white/[0.03] transition-colors"
          >
            <td className="pl-4 py-3 text-zinc-500 num">{s.rank}</td>
            <td className="px-2 py-3">
              <div className="font-semibold text-zinc-100">{s.symbol}</div>
              <div className="text-zinc-500 truncate max-w-[14rem]">{s.name}</div>
            </td>
            <td className="px-4 py-3 text-right num text-zinc-100 font-medium">
              {s.price.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            </td>
            <td className="px-4 py-3 text-right num">
              <ProfitText value={s.rate / 100} format={formatPct} />
            </td>
            <td className="px-4 py-3 text-right num text-zinc-400 hidden sm:table-cell">
              {Math.round(s.tradingValue).toLocaleString("en-US")}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
