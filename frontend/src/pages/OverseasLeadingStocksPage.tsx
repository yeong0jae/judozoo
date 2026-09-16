import { useEffect, useState } from "react";
import { useOverseasLeaders, useOverseasRanking } from "../api/queries";
import type { OverseasStockRankItem } from "../types";
import { formatFetchedAt, formatPct } from "../lib/format";
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
import { useArrowStockNav } from "../lib/useArrowStockNav";
import LoginGate from "../components/common/LoginGate";
import { useMe } from "../api/auth";


export default function OverseasLeadingStocks({
  market,
  onMarket,
}: {
  market: StockMarket;
  onMarket: (m: StockMarket) => void;
}) {
  // 당일 등락률 임계값(%) — 헤더 티커와 공유한다.
  const [minChangeRate, setMinChangeRate] = useOverseasMinChangeRate();

  const leadersQ = useOverseasLeaders();
  const { data, isLoading, isFetching, dataUpdatedAt } = useOverseasRanking(minChangeRate);
  // 국내와 같은 구조 — 위는 서버가 고른 주도주, 아래는 나머지를 거래대금 순으로.
  // 등락률 임계값은 아래 구간에만 걸린다.
  const leaders = leadersQ.data ?? [];
  const leaderSymbols = new Set(leaders.map((s) => s.symbol));
  const rest = (data ?? []).filter((s) => !leaderSymbols.has(s.symbol));
  const stocks = [...leaders, ...rest];
  const [openSymbol, setOpenSymbol] = useState<string | null>(null);
  const { data: me } = useMe();

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

  const selector = (
    <ChangeRateSelector value={minChangeRate} onChange={setMinChangeRate} />
  );

  return (
    <div className="space-y-4">
      <Header totalCount={stocks.length} loading={isFetching} fetchedAt={dataUpdatedAt} />

      {/* 선택기가 맨 위에 있지만 걸리는 곳은 아래 "후보" 구간뿐이다 */}
      <div className={openSymbol ? "grid grid-cols-1 lg:grid-cols-[9fr_11fr] gap-6" : ""}>
        <MarketToggle value={market} onChange={onMarket} trailing={selector} />
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
          {isLoading || leadersQ.isLoading ? (
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
                leaders={leaders}
                rest={rest}
                selectedSymbol={openSymbol}
                onOpen={setOpenSymbol}
              />
              <RankingCards
                leaders={leaders}
                rest={rest}
                selectedSymbol={openSymbol}
                onOpen={setOpenSymbol}
              />
            </>
          )}
        </section>

        {openSymbol && (
          <aside className="lg:sticky lg:top-6">
            {me?.authenticated ? (
              <OverseasStockDetailPanel exchange={selected?.exchange ?? null} symbol={openSymbol} />
            ) : (
              <LoginGate
                title="종목 상세"
                description="필터 평가와 분봉, 일봉을 종목별로 봅니다. 로그인하면 확인할 수 있습니다."
              />
            )}
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
  fetchedAt,
}: {
  totalCount: number | undefined;
  loading: boolean;
  /** 목록 응답에 조회 시각이 없어 브라우저가 받은 시각을 쓴다. */
  fetchedAt: number;
}) {
  return (
    <PageHeader
      title="오늘의 주도주"
      count={totalCount}
      queriedAt={formatFetchedAt(fetchedAt)}
      loading={loading}
    />
  );
}

// ============================================================
// Ranking table (데스크톱)
// ============================================================

function RankingTable({
  leaders,
  rest,
  selectedSymbol,
  onOpen,
}: {
  leaders: OverseasStockRankItem[];
  rest: OverseasStockRankItem[];
  selectedSymbol: string | null;
  onOpen: (symbol: string) => void;
}) {
  const section = (list: OverseasStockRankItem[]) =>
    list.map((s, idx) => (
      <Row
        key={s.symbol}
        stock={s}
        rank={idx + 1}
        line={idx !== list.length - 1}
        isSelected={selectedSymbol === s.symbol}
        onOpen={onOpen}
      />
    ));

  return (
    <table className="hidden md:table w-full text-xs border-separate border-spacing-y-0">
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
        {leaders.length > 0 && <GroupHeader label="주도주" />}
        {section(leaders)}
        {/* 통과 종목이 없어도 머리는 그린다 — 기준이 걸렸다는 걸 알 수 있게 */}
        <GroupHeader label="후보" hint="거래대금 순" />
        {rest.length > 0 ? (
          section(rest)
        ) : (
          <tr>
            <td colSpan={5} className="px-4 py-6 text-center text-zinc-500">
              이 기준을 통과한 종목이 없습니다
            </td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

/** 목록 한 줄. 심볼은 이름 오른쪽에 — 아래 줄로 내리면 행이 두 줄 높이가 된다. */
function Row({
  stock,
  rank,
  line,
  isSelected,
  onOpen,
}: {
  stock: OverseasStockRankItem;
  rank: number;
  line: boolean;
  isSelected: boolean;
  onOpen: (symbol: string) => void;
}) {
  return (
    <tr
      data-stock-code={stock.symbol}
      className={`cursor-pointer transition-colors hover:[&>td]:bg-zinc-850 [&>td:first-child]:rounded-l-xl [&>td:last-child]:rounded-r-xl ${
        line ? "[&>td]:border-b [&>td]:border-zinc-800/60" : ""
      } ${isSelected ? "[&>td]:bg-selected" : ""}`}
      onClick={() => onOpen(stock.symbol)}
    >
      <td className="pl-4 py-3.5 text-zinc-500 num w-10">{rank}</td>
      <td className="px-2 py-3.5">
        <div className="flex items-center gap-2.5 min-w-0">
          <StockAvatar name={stock.name} code={stock.symbol} size={28} />
          <span className="font-semibold text-zinc-100 truncate max-w-[12rem]">{stock.name}</span>
          <span className="text-[11.5px] text-zinc-500 num shrink-0">{stock.symbol}</span>
        </div>
      </td>
      <td className="px-4 py-3.5 text-right num font-medium text-zinc-100">
        <NumUsd value={stock.price} prefix="" />
      </td>
      <td className="px-4 py-3.5 text-right num font-medium">
        <ProfitText value={stock.rate / 100} format={formatPct} />
      </td>
      <td className="px-4 py-3.5 text-right num text-zinc-400">
        {Math.round(stock.tradingValue).toLocaleString("en-US")}
      </td>
    </tr>
  );
}

/** 구간 머리 — 국내와 같은 규칙. */
function GroupHeader({
  label,
  hint,
}: {
  label: string;
  hint?: string;
}) {
  return (
    <tr>
      <td colSpan={5} className="px-4 pt-4 pb-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[15.5px] font-bold text-zinc-100 tracking-tight">{label}</span>
          {hint && <span className="text-xs text-zinc-500 font-normal">{hint}</span>}
        </div>
      </td>
    </tr>
  );
}

// ============================================================
// Ranking cards (모바일)
// ============================================================

function RankingCards({
  leaders,
  rest,
  selectedSymbol,
  onOpen,
}: {
  leaders: OverseasStockRankItem[];
  rest: OverseasStockRankItem[];
  selectedSymbol: string | null;
  onOpen: (symbol: string) => void;
}) {
  const section = (list: OverseasStockRankItem[]) =>
    list.map((s, idx) => (
      <Card
        key={s.symbol}
        stock={s}
        rank={idx + 1}
        line={idx !== list.length - 1}
        isSelected={selectedSymbol === s.symbol}
        onOpen={onOpen}
      />
    ));

  return (
    <div className="md:hidden">
      {leaders.length > 0 && <CardGroupHeader label="주도주" />}
      {section(leaders)}
      <CardGroupHeader label="후보" hint="거래대금 순" />
      {rest.length > 0 ? (
        section(rest)
      ) : (
        <p className="px-4 py-6 text-center text-xs text-zinc-500">
          이 기준을 통과한 종목이 없습니다
        </p>
      )}
    </div>
  );
}

function Card({
  stock,
  rank,
  line,
  isSelected,
  onOpen,
}: {
  stock: OverseasStockRankItem;
  rank: number;
  line: boolean;
  isSelected: boolean;
  onOpen: (symbol: string) => void;
}) {
  return (
    <div
      data-stock-code={stock.symbol}
      className={`rounded-xl px-4 py-3.5 flex flex-col gap-1 cursor-pointer ${
        line ? "border-b border-zinc-800/60" : ""
      } ${isSelected ? "bg-selected" : ""}`}
      onClick={() => onOpen(stock.symbol)}
    >
      {/* 1행: 순위 · 아바타 · 이름 · 현재가 */}
      <div className="flex items-center gap-2">
        <span className="text-zinc-500 text-xs num w-4 shrink-0">{rank}</span>
        <StockAvatar name={stock.name} code={stock.symbol} size={28} />
        <span className="font-semibold truncate flex-1 min-w-0">{stock.name}</span>
        <NumUsd value={stock.price} prefix="" className="num shrink-0 font-medium text-zinc-100" />
      </div>
      {/* 2행: 심볼 · 등락률 */}
      <div className="flex items-center gap-2 pl-[3.25rem]">
        <span className="text-xs text-zinc-500 num truncate flex-1 min-w-0">{stock.symbol}</span>
        <ProfitText value={stock.rate / 100} format={formatPct} className="num text-xs shrink-0" />
      </div>
    </div>
  );
}

function CardGroupHeader({
  label,
  hint,
}: {
  label: string;
  hint?: string;
}) {
  return (
    <div className="bg-zinc-950 px-4 pt-4 pb-2 flex flex-wrap items-center gap-2">
      <span className="text-[15.5px] font-bold text-zinc-100 tracking-tight">{label}</span>
      {hint && <span className="text-xs text-zinc-500">{hint}</span>}
    </div>
  );
}
