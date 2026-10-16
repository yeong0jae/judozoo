import { useEffect, useState } from "react";
import { POOL_REFRESH_MS, useInsightReasons, useOverseasLeaders, useOverseasCandidates } from "../api/queries";
import type { InsightReasonItem, OverseasStockRankItem } from "../types";
import { formatPct } from "../lib/format";
import NumUsd from "../components/common/NumUsd";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import ChangeRateSelector from "../components/common/ChangeRateSelector";
import OverseasStockDetailPanel from "../components/common/OverseasStockDetailPanel";
import PageHeader from "../components/layout/PageHeader";
import { useOverseasMinChangeRate } from "../lib/changeRate";
import MarketToggle, { type StockMarket } from "../components/common/MarketToggle";
import { useArrowStockNav } from "../lib/useArrowStockNav";
import LoginGate from "../components/common/LoginGate";
import { ReasonCard, ReasonHead, ReasonLine } from "../components/common/InsightReason";
import ListDetail, { useMobileDetail } from "../components/layout/ListDetail";
import { useMe } from "../api/auth";
import { isEtWeekend, useMarketSessions, usOpenClock } from "../lib/marketSession";
import LeaderGuide from "../components/common/LeaderGuide";

export default function OverseasLeadingStocks({
  market,
  onMarket,
}: {
  market: StockMarket;
  onMarket: (m: StockMarket) => void;
}) {
  const [minChangeRate, setMinChangeRate] = useOverseasMinChangeRate();

  const leadersQ = useOverseasLeaders();
  const { data, isLoading, isError, isFetching, dataUpdatedAt } = useOverseasCandidates(minChangeRate);
  const leaders = leadersQ.data ?? [];
  const leaderSymbols = new Set(leaders.map((s) => s.symbol));
  const rest = (data ?? []).filter((s) => !leaderSymbols.has(s.symbol));
  const stocks = [...leaders, ...rest];
  const [openSymbol, setOpenSymbol] = useState<string | null>(null);
  const { data: me } = useMe();
  const { us, now } = useMarketSessions();
  const mobile = useMobileDetail();
  const reasons = useInsightReasons("us").data;

  useArrowStockNav(
    stocks.map((s) => s.symbol),
    openSymbol,
    setOpenSymbol,
  );

  useEffect(() => {
    if (stocks.length === 0) return;
    if (openSymbol === null || !stocks.some((s) => s.symbol === openSymbol)) {
      setOpenSymbol(stocks[0].symbol);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stocks]);

  const selected = stocks.find((s) => s.symbol === openSymbol) ?? null;
  const open = (symbol: string) => {
    setOpenSymbol(symbol);
    mobile.show();
  };

  const list = (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-3 lg:px-3.5">
        <Header totalCount={stocks.length} loading={isFetching} fetchedAt={dataUpdatedAt} />
        <MarketToggle
          value={market}
          onChange={onMarket}
          trailing={<ChangeRateSelector value={minChangeRate} onChange={setMinChangeRate} />}
        />
      </div>

      {isLoading || leadersQ.isLoading ? (
        <div className="space-y-3 p-3">
          {Array.from({ length: 10 }).map((_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : isError || leadersQ.isError ? (
        <EmptyState message="해외 주도주를 불러오지 못했습니다" />
      ) : stocks.length === 0 ? (
        <EmptyRanking live={us !== null} now={now} />
      ) : (
        <RankingList
          leaders={leaders}
          rest={rest}
          minChangeRate={minChangeRate}
          selectedSymbol={openSymbol}
          onOpen={open}
          reasons={reasons}
        />
      )}
    </div>
  );

  const reason = openSymbol ? reasons?.get(openSymbol) : undefined;
  const detail = me?.authenticated ? (
    <OverseasStockDetailPanel
      exchange={selected?.exchange ?? null}
      symbol={openSymbol}
      preview={selected ?? undefined}
      onBack={mobile.hide}
      insight={<ReasonCard item={reason} member />}
    />
  ) : (
    <div className="flex flex-col gap-6">
      {selected && (
        <ReasonCard
          item={reason}
          member={false}
          head={
            <ReasonHead
              name={selected.name}
              code={selected.symbol}
              rate={<ProfitText value={selected.rate / 100} format={formatPct} />}
            />
          }
        />
      )}
      <LoginGate
        title="종목 상세"
        description="주도주 조건과 분봉, 일봉을 종목별로 봅니다. 로그인 후 확인할 수 있습니다."
      />
    </div>
  );

  return <ListDetail list={list} detail={detail} detailOpen={mobile.open} />;
}

function EmptyRanking({ live, now }: { live: boolean; now: Date }) {
  if (live) return <EmptyState message="데이터가 없습니다" />;
  const when = isEtWeekend(now) ? "월요일 프리마켓" : "프리마켓";
  return (
    <EmptyState message="해외 주도주가 없습니다" hint={`${when} ${usOpenClock(now)}에 채워집니다.`} />
  );
}

function Header({
  totalCount,
  loading,
  fetchedAt,
}: {
  totalCount: number | undefined;
  loading: boolean;
  fetchedAt: number;
}) {
  return (
    <PageHeader
      title="오늘의 주도주"
      count={totalCount}
      fetchedAt={fetchedAt}
      refreshMs={POOL_REFRESH_MS}
      loading={loading}
    />
  );
}

const ROW_COLS =
  "grid-cols-[1.25rem_minmax(0,1fr)_6.5rem_5.5rem_4.5rem] 2xl:grid-cols-[1.25rem_minmax(0,1fr)_7.25rem_6rem_5rem]";

function compactUsd(v: number): string {
  if (v >= 1e9) return `$${(v / 1e9).toFixed(2)}B`;
  if (v >= 1e6) return `$${(v / 1e6).toFixed(1)}M`;
  return `$${Math.round(v).toLocaleString("en-US")}`;
}

function RankingList({
  leaders,
  rest,
  minChangeRate,
  selectedSymbol,
  onOpen,
  reasons,
}: {
  leaders: OverseasStockRankItem[];
  rest: OverseasStockRankItem[];
  minChangeRate: number;
  selectedSymbol: string | null;
  onOpen: (symbol: string) => void;
  reasons?: Map<string, InsightReasonItem>;
}) {
  const maxValue = Math.max(1, ...[...leaders, ...rest].map((s) => s.tradingValue));
  const section = (list: OverseasStockRankItem[], Item: typeof Row | typeof Card) =>
    list.map((s, idx) => (
      <Item
        key={s.symbol}
        stock={s}
        rank={idx + 1}
        valueRatio={s.tradingValue / maxValue}
        isSelected={selectedSymbol === s.symbol}
        onOpen={onOpen}
        reason={reasons?.get(s.symbol)}
      />
    ));
  const restHint = `거래대금 순 · 등락률 ${minChangeRate > 0 ? "+" : ""}${minChangeRate}% 이상`;
  const empty = <p className="px-4 py-6 text-center text-xs text-zinc-500">이 기준을 통과한 종목이 없습니다</p>;

  return (
    <>
      <div className="hidden lg:block">
        <div className={`grid ${ROW_COLS} gap-x-3 px-3.5 pb-1.5 text-xs text-zinc-500`}>
          <span />
          <span>종목</span>
          <span className="text-right">거래대금</span>
          <span className="text-right">현재가</span>
          <span className="text-right">등락률</span>
        </div>
        {leaders.length > 0 && <GroupHeader label="주도주" count={leaders.length} guide />}
        {section(leaders, Row)}
        <GroupHeader label="후보" count={rest.length} hint={restHint} />
        {rest.length > 0 ? section(rest, Row) : empty}
      </div>
      <div className="lg:hidden">
        {leaders.length > 0 && <GroupHeader label="주도주" count={leaders.length} guide />}
        {section(leaders, Card)}
        <GroupHeader label="후보" count={rest.length} hint={restHint} />
        {rest.length > 0 ? section(rest, Card) : empty}
      </div>
    </>
  );
}

type ItemProps = {
  stock: OverseasStockRankItem;
  rank: number;
  valueRatio: number;
  isSelected: boolean;
  onOpen: (symbol: string) => void;
  reason?: InsightReasonItem;
};

function Row({ stock, rank, valueRatio, isSelected, onOpen, reason }: ItemProps) {
  return (
    <button
      type="button"
      data-stock-code={stock.symbol}
      onClick={() => onOpen(stock.symbol)}
      className={`grid w-full ${ROW_COLS} items-center gap-x-3 rounded-xl px-3.5 py-2.5 text-left transition-colors ${
        isSelected ? "bg-selected" : "hover:bg-zinc-850"
      }`}
    >
      <span className="num text-right text-xs text-zinc-500">{rank}</span>
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="truncate text-[13.5px] text-zinc-100">{stock.name}</span>
        <span className="num text-[11px] text-zinc-500">{stock.symbol}</span>
      </span>
      <span className="flex flex-col items-end gap-1.5">
        <span className="num text-xs text-zinc-400">{compactUsd(stock.tradingValue)}</span>
        <span className="relative h-0.5 w-full overflow-hidden rounded-full bg-zinc-850">
          <span className="absolute inset-y-0 right-0 rounded-full bg-zinc-500" style={{ width: `${valueRatio * 100}%` }} />
        </span>
      </span>
      <NumUsd value={stock.price} prefix="" className="num text-right text-[13.5px] text-zinc-100" />
      <span className="num text-right text-xs">
        <ProfitText value={stock.rate / 100} format={formatPct} />
      </span>
      <ReasonLine item={reason} className="col-span-4 col-start-2 mt-2" />
    </button>
  );
}

function Card({ stock, rank, isSelected, onOpen, reason }: ItemProps) {
  return (
    <button
      type="button"
      data-stock-code={stock.symbol}
      onClick={() => onOpen(stock.symbol)}
      className={`flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2.5 text-left ${isSelected ? "bg-selected" : ""}`}
    >
      <span className="num w-4 shrink-0 text-right text-[11px] text-zinc-500">{rank}</span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="truncate text-[13.5px] text-zinc-100">{stock.name}</span>
        <span className="num text-[11px] text-zinc-500">
          {stock.symbol} · {compactUsd(stock.tradingValue)}
        </span>
        <ReasonLine item={reason} className="mt-1" />
      </span>
      <span className="flex shrink-0 flex-col items-end gap-0.5">
        <NumUsd value={stock.price} prefix="" className="num text-[13.5px] text-zinc-100" />
        <ProfitText value={stock.rate / 100} format={formatPct} className="num text-xs" />
      </span>
    </button>
  );
}

function GroupHeader({ label, count, hint, guide }: { label: string; count: number; hint?: string; guide?: boolean }) {
  return (
    <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 px-2.5 pb-1.5 pt-4 lg:px-3.5">
      <span className="text-[15px] font-bold tracking-tight text-zinc-100">{label}</span>
      <span className="num text-xs text-zinc-400">{count}</span>
      {guide && (
        <span className="self-center">
          <LeaderGuide market="overseas" />
        </span>
      )}
      {hint && <span className="text-xs text-zinc-500">{hint}</span>}
    </div>
  );
}
