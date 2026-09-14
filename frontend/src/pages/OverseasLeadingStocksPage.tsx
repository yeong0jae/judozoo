import { Fragment, useEffect, useState } from "react";
import { useOverseasRanking } from "../api/queries";
import type { OverseasStockRankItem } from "../types";
import { formatPct } from "../lib/format";
import NumUsd from "../components/common/NumUsd";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import StockAvatar from "../components/common/StockAvatar";
import ChangeRateSelector from "../components/common/ChangeRateSelector";
import OverseasStockDetailPanel from "../components/common/OverseasStockDetailPanel";
import PageHeader from "../components/layout/PageHeader";
import { useOverseasMinChangeRate } from "../lib/changeRate";
import MarketToggle, { type StockMarket } from "../components/common/MarketToggle";
import HolidayBanner from "../components/common/HolidayBanner";
import { useArrowStockNav } from "../lib/useArrowStockNav";


export default function OverseasLeadingStocks({
  market,
  onMarket,
}: {
  market: StockMarket;
  onMarket: (m: StockMarket) => void;
}) {
  // 당일 등락률 임계값(%) — 헤더 티커와 공유한다.
  const [minChangeRate, setMinChangeRate] = useOverseasMinChangeRate();

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
    <div className="space-y-4">
      <HolidayBanner region="US" />
      <Header totalCount={data?.length} loading={isFetching} />

      {/* 토글+필터는 목록 컬럼 폭에 맞춰(필터가 리스트 오른쪽 끝에 정렬) */}
      <div className={openSymbol ? "grid grid-cols-1 lg:grid-cols-[9fr_11fr] gap-6" : ""}>
        <MarketToggle
          value={market}
          onChange={onMarket}
          trailing={<ChangeRateSelector value={minChangeRate} onChange={setMinChangeRate} />}
        />
      </div>

      {/* 종목 선택 시 좌(목록) / 우(상세) 2분할, 선택 없으면 목록 전체 폭 */}
      <div
        className={
          openSymbol
            ? "grid grid-cols-1 lg:grid-cols-[9fr_11fr] gap-6 items-start"
            : ""
        }
      >
        <section>
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
            <OverseasStockDetailPanel exchange={selected?.exchange ?? null} symbol={openSymbol} />
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
    <PageHeader
      title="주도주 필터"
      subtitle="나스닥·뉴욕·아멕스에서 돈이 몰리고 오른 종목 · 누르면 필터 3개 평가"
      count={totalCount}
      loading={loading}
    />
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
    <table className="hidden md:table w-full text-xs border-separate border-spacing-y-1">
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
                className={`cursor-pointer transition-colors hover:[&>td]:bg-zinc-850 [&>td:first-child]:rounded-l-xl [&>td:last-child]:rounded-r-xl ${
                  isSelected ? "[&>td]:bg-selected" : ""
                }`}
                onClick={() => onOpen(s.symbol)}
              >
                <td className="pl-4 py-3.5 text-zinc-500 num w-10">{s.rank}</td>
                <td className="px-2 py-3.5">
                  <div className="flex items-center gap-3">
                    <StockAvatar name={s.name} code={s.symbol} />
                    <div className="min-w-0">
                      <div className="font-semibold text-zinc-100 truncate max-w-[12rem]">{s.name}</div>
                      <div className="text-xs text-zinc-500 num">{s.symbol}</div>
                    </div>
                  </div>
                </td>
                <td className="px-4 py-3.5 text-right num font-medium text-zinc-100">
                  <NumUsd value={s.price} prefix="" />
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
    <tr aria-hidden>
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
    <div className="md:hidden space-y-1">
      {stocks.map((s, idx) => {
        const isSelected = selectedSymbol === s.symbol;
        return (
          <Fragment key={s.symbol}>
            {idx === 0 && <CardGroupHeader label="거래대금 1, 2, 3위" />}
            {idx === 3 && <CardGroupHeader label="주도주 후보" />}
            <div
              data-stock-code={s.symbol}
              className={`rounded-xl px-4 py-3.5 flex flex-col gap-1 cursor-pointer ${
                isSelected ? "bg-selected" : ""
              }`}
              onClick={() => onOpen(s.symbol)}
            >
              {/* 1행: 순위 · 아바타 · 심볼 · 현재가 */}
              <div className="flex items-center gap-2">
                <span className="text-zinc-500 text-xs num w-4 shrink-0">{s.rank}</span>
                <StockAvatar name={s.name} code={s.symbol} size={26} />
                <span className="font-semibold truncate flex-1 min-w-0">{s.name}</span>
                <NumUsd value={s.price} prefix="" className="num shrink-0 font-medium text-zinc-100" />
              </div>
              {/* 2행: 심볼 · 등락률 */}
              <div className="flex items-center gap-2 pl-[3.25rem]">
                <span className="text-xs text-zinc-500 num truncate flex-1 min-w-0">
                  {s.symbol}
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
