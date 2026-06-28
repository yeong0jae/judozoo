import { useEffect, useState } from "react";
import { useOverseasRanking } from "../api/queries";
import type { OverseasStockRankItem } from "../types";
import { formatPct } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import StockAvatar from "../components/common/StockAvatar";
import { useArrowStockNav } from "../lib/useArrowStockNav";

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
  const { data, isLoading, isFetching } = useOverseasRanking();
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
          해외주식 거래대금 순위
          <span
            className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
              loading ? "animate-ping" : "animate-pulse"
            }`}
            aria-label={loading ? "갱신 중" : "대기"}
          />
        </h2>
        <p className="text-xs text-zinc-500 mt-0.5">
          나스닥·뉴욕·아멕스 통합 거래대금 상위 40위 · 30초 자동 갱신
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
          <th className="px-4 py-2.5 text-left font-medium">거래소</th>
          <th className="px-4 py-2.5 text-right font-medium">현재가</th>
          <th className="px-4 py-2.5 text-right font-medium">등락률</th>
          <th className="px-4 py-2.5 text-right font-medium">거래대금(USD)</th>
        </tr>
      </thead>
      <tbody>
        {stocks.map((s) => {
          const isSelected = selectedSymbol === s.symbol;
          return (
            <tr
              key={s.symbol}
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
              <td className="px-4 py-3.5 text-zinc-400">{exchangeLabel(s.exchange)}</td>
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
          );
        })}
      </tbody>
    </table>
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
      {stocks.map((s) => {
        const isSelected = selectedSymbol === s.symbol;
        return (
          <div
            key={s.symbol}
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
            {/* 2행: 거래소·종목명 · 등락률 */}
            <div className="flex items-center gap-2 pl-[3.25rem]">
              <span className="text-xs text-zinc-500 truncate flex-1 min-w-0">
                {exchangeLabel(s.exchange)} · {s.name}
              </span>
              <ProfitText
                value={s.rate / 100}
                format={formatPct}
                className="num text-xs shrink-0"
              />
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ============================================================
// 상세 패널 — 선택 종목 기본 정보
// ============================================================

function StockDetailPanel({ stock }: { stock: OverseasStockRankItem | null }) {
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
        <div className="flex items-center gap-3">
          <StockAvatar name={stock.symbol} code={stock.symbol} size={36} />
          <div className="min-w-0">
            <div className="flex items-center flex-wrap gap-x-2">
              <span className="text-base font-semibold">{stock.symbol}</span>
              <span className="text-xs text-zinc-500">{exchangeLabel(stock.exchange)}</span>
            </div>
            <div className="text-xs text-zinc-400 truncate">{stock.ename || stock.name}</div>
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
