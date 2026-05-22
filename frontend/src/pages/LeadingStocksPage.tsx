import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLeadingStockCandidates, useLeadingStockDetail } from "../api/queries";
import type { CandidateStockItem, FilterResultItem } from "../types";
import {
  formatKRW,
  formatPct,
  formatPrice,
  formatRelative,
} from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";

/**
 * 키움 마스터 코드 — 거래 ID로는 6자리 단축코드만 사용.
 * "009150_AL" 같이 거래소 접미사가 붙어 오면 "_" 앞쪽으로 잘라낸다.
 */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

export default function LeadingStocksPage() {
  const candidatesQ = useLeadingStockCandidates();
  const [openCode, setOpenCode] = useState<string | null>(null);

  const data = candidatesQ.data;
  const stocks = data?.stocks ?? [];

  return (
    <div className="space-y-6">
      <Header
        totalCount={data?.totalCount}
        queriedAt={data?.queriedAt}
        loading={candidatesQ.isFetching}
      />

      <section className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden">
        {candidatesQ.isLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : stocks.length === 0 ? (
          <EmptyState message="조건을 통과한 후보가 없습니다" />
        ) : (
          <CandidatesTable
            stocks={stocks}
            onOpen={(code) => setOpenCode(code)}
          />
        )}
      </section>

      {openCode && (
        <DetailModal
          stockCode={openCode}
          onClose={() => setOpenCode(null)}
        />
      )}
    </div>
  );
}

// ============================================================
// Header
// ============================================================

function Header({
  totalCount,
  queriedAt,
  loading,
}: {
  totalCount: number | undefined;
  queriedAt: string | undefined;
  loading: boolean;
}) {
  return (
    <div className="flex items-baseline justify-between">
      <div>
        <h2 className="text-xl font-bold">주도주 후보</h2>
        <p className="text-xs text-zinc-500 mt-0.5">
          거래대금 상위 + 당일 등락률 필터 통과 종목 · 5초 자동 갱신
        </p>
      </div>
      <div className="text-xs text-zinc-500 flex items-center gap-2">
        {loading && <span className="text-emerald-700">갱신 중…</span>}
        {queriedAt && <span>조회 {formatRelative(queriedAt)}</span>}
        {typeof totalCount === "number" && (
          <span className="text-zinc-300 font-medium">{totalCount}건</span>
        )}
      </div>
    </div>
  );
}

// ============================================================
// Candidates table
// ============================================================

function CandidatesTable({
  stocks,
  onOpen,
}: {
  stocks: CandidateStockItem[];
  onOpen: (stockCode: string) => void;
}) {
  const navigate = useNavigate();
  return (
    <table className="w-full text-sm">
      <thead className="bg-zinc-950 text-zinc-500 text-xs uppercase tracking-wider">
        <tr>
          <th className="px-4 py-2.5 text-left w-12">순위</th>
          <th className="px-4 py-2.5 text-left">종목</th>
          <th className="px-4 py-2.5 text-right">현재가</th>
          <th className="px-4 py-2.5 text-right">등락률</th>
          <th className="px-4 py-2.5 text-right">거래대금</th>
          <th className="px-4 py-2.5 w-24"></th>
        </tr>
      </thead>
      <tbody>
        {stocks.map((s) => {
          const code = shortCode(s.stockCode);
          return (
            <tr
              key={s.stockCode}
              className="border-t border-zinc-800 hover:bg-zinc-800/40 cursor-pointer"
              onClick={() => onOpen(s.stockCode)}
            >
              <td className="px-4 py-3 text-zinc-400">{s.rank}</td>
              <td className="px-4 py-3">
                <div className="font-medium">{s.stockName}</div>
                <div className="text-xs text-zinc-500 num">{code}</div>
              </td>
              <td className="px-4 py-3 text-right num">
                {formatPrice(s.currentPrice)}
              </td>
              <td className="px-4 py-3 text-right num">
                <ProfitText value={s.priceChangeRate} format={formatPct} />
              </td>
              <td className="px-4 py-3 text-right num text-zinc-300">
                {formatKRW(s.accumulatedTradingValue)}
              </td>
              <td className="px-4 py-3 text-right">
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    navigate(
                      `/command?stockCode=${code}&stockName=${encodeURIComponent(s.stockName)}`,
                    );
                  }}
                  className="px-3 py-1.5 rounded-md text-xs font-medium bg-emerald-700 hover:bg-emerald-600 text-white"
                >
                  매매
                </button>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

// ============================================================
// Detail modal
// ============================================================

function DetailModal({
  stockCode,
  onClose,
}: {
  stockCode: string;
  onClose: () => void;
}) {
  const detailQ = useLeadingStockDetail(stockCode);
  const detail = detailQ.data;

  return (
    <div
      className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4"
      onClick={onClose}
    >
      <div
        className="bg-zinc-900 border border-zinc-800 rounded-lg max-w-2xl w-full max-h-[80vh] overflow-hidden flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="flex items-center justify-between px-6 py-4 border-b border-zinc-800">
          <div>
            <div className="text-lg font-semibold">
              {detail?.stockName ?? "…"}
              <span className="text-xs text-zinc-500 ml-2 num">
                {shortCode(stockCode)}
              </span>
            </div>
            {detail && (
              <div className="text-sm text-zinc-400 mt-0.5">
                <span className="num">{formatPrice(detail.currentPrice)}</span>{" "}
                <ProfitText
                  value={detail.priceChangeRate}
                  format={formatPct}
                  className="num ml-1"
                />
              </div>
            )}
          </div>
          <button
            onClick={onClose}
            className="text-zinc-500 hover:text-zinc-200 text-xl"
            aria-label="닫기"
          >
            ×
          </button>
        </header>

        <div className="flex-1 overflow-y-auto p-6">
          {detailQ.isLoading ? (
            <div className="space-y-2">
              {Array.from({ length: 6 }).map((_, i) => (
                <Skeleton key={i} className="h-12 w-full" />
              ))}
            </div>
          ) : detailQ.isError ? (
            <p className="text-sm text-rose-700">상세 정보를 불러올 수 없습니다</p>
          ) : detail ? (
            <FilterResultsList results={detail.filterResults} />
          ) : null}
        </div>
      </div>
    </div>
  );
}

function FilterResultsList({ results }: { results: FilterResultItem[] }) {
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
            r.passed
              ? "bg-emerald-50 border-emerald-200"
              : "bg-rose-50 border-rose-200"
          }`}
        >
          <span className="text-sm mt-0.5">{r.passed ? "✓" : "✗"}</span>
          <div className="flex-1 min-w-0">
            <div className="text-sm font-medium">{r.filterName}</div>
            <div className="text-xs text-zinc-500 mt-0.5">
              기준: {r.criteriaDescription}
            </div>
          </div>
          <div className="text-sm num shrink-0">{r.actualValue}</div>
        </div>
      ))}
    </div>
  );
}
