import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { POOL_REFRESH_MS, useInsightReasons, useLeadingStockCandidates, useLeadingStockLeaders } from "../api/queries";
import type { CandidateStockItem, InsightReasonItem } from "../types";
import { formatKoreanMoney, formatPct } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import { useMinChangeRate } from "../lib/changeRate";
import PageHeader from "../components/layout/PageHeader";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import FlashOnChange from "../components/common/FlashOnChange";
import NumWon from "../components/common/NumWon";
import StockDetailPanel from "../components/common/StockDetailPanel";
import LoginGate from "../components/common/LoginGate";
import { ReasonCard, ReasonHead, ReasonLine } from "../components/common/InsightReason";
import { useMe } from "../api/auth";
import ChangeRateSelector from "../components/common/ChangeRateSelector";
import { useArrowStockNav } from "../lib/useArrowStockNav";
import OverseasLeadingStocks from "./OverseasLeadingStocksPage";
import ListDetail, { useMobileDetail } from "../components/layout/ListDetail";
import MarketToggle from "../components/common/MarketToggle";
import { loadMarket, rememberMarket, type StockMarket } from "../lib/stockMarket";
import LeaderGuide from "../components/common/LeaderGuide";

function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

type Flash = "new" | "promoted" | "demoted" | null;

const FLASH_MS = 4000;

function useMembership(
  codes: string[],
  session: string | null,
): { entered: Set<string>; left: Set<string> } {
  const key = codes.join(",");
  const prevRef = useRef<{ session: string; codes: Set<string> } | null>(null);
  const [entered, setEntered] = useState<Set<string>>(new Set());
  const [left, setLeft] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (session === null) return;

    const current = new Set(key ? key.split(",") : []);
    const prev = prevRef.current;
    prevRef.current = { session, codes: current };
    if (prev === null || prev.session !== session || prev.codes.size === 0) return;

    const 들어옴 = [...current].filter((c) => !prev.codes.has(c));
    const 나감 = [...prev.codes].filter((c) => !current.has(c));
    if (들어옴.length === 0 && 나감.length === 0) return;

    const 잠시 = (
      codes: string[],
      set: (fn: (p: Set<string>) => Set<string>) => void,
    ) => {
      if (codes.length === 0) return [];
      set((p) => new Set([...p, ...codes]));
      return codes.map((c) =>
        setTimeout(() => {
          set((p) => {
            const next = new Set(p);
            next.delete(c);
            return next;
          });
        }, FLASH_MS),
      );
    };

    const timers = [...잠시(들어옴, setEntered), ...잠시(나감, setLeft)];
    return () => timers.forEach(clearTimeout);
  }, [key, session]);

  return { entered, left };
}

function useSlideOnReorder(ref: RefObject<HTMLElement>, orderKey: string) {
  const prevTops = useRef<Map<string, number>>(new Map());

  useLayoutEffect(() => {
    const root = ref.current;
    if (!root) return;

    const nodes = [...root.querySelectorAll<HTMLElement>("[data-stock-code]")];
    const tops = new Map<string, number>();
    for (const node of nodes) {
      const code = node.dataset.stockCode!;
      const top = node.getBoundingClientRect().top;
      tops.set(code, top);

      const before = prevTops.current.get(code);
      const delta = before === undefined ? 0 : before - top;
      if (delta === 0) continue;

      node.style.transform = `translateY(${delta}px)`;
      requestAnimationFrame(() => {
        node.classList.add("row-sliding");
        node.style.transform = "";
      });
      node.addEventListener("transitionend", () => node.classList.remove("row-sliding"), {
        once: true,
      });
    }
    prevTops.current = tops;
  }, [ref, orderKey]);
}

export default function LeadingStocksPage() {
  const [market, setMarket] = useState<StockMarket>(loadMarket);
  useEffect(() => {
    rememberMarket(market);
  }, [market]);

  return market === "domestic" ? (
    <DomesticLeadingStocks market={market} onMarket={setMarket} />
  ) : (
    <OverseasLeadingStocks market={market} onMarket={setMarket} />
  );
}

function DomesticLeadingStocks({
  market,
  onMarket,
}: {
  market: StockMarket;
  onMarket: (m: StockMarket) => void;
}) {
  const { data: me } = useMe();
  const [minChangeRate, setMinChangeRate] = useMinChangeRate();
  const leadersQ = useLeadingStockLeaders();
  const candidatesQ = useLeadingStockCandidates(minChangeRate);
  const [openCode, setOpenCode] = useState<string | null>(null);
  const mobile = useMobileDetail();
  const reasons = useInsightReasons("kr").data;

  const data = candidatesQ.data;
  const leaders = leadersQ.data?.leaders ?? [];
  const leaderCodes = new Set(leaders.map((s) => s.stockCode));
  const rest = (data?.stocks ?? []).filter((s) => !leaderCodes.has(s.stockCode));
  const stocks = [...leaders, ...rest];

  useArrowStockNav(
    stocks.map((s) => s.stockCode),
    openCode,
    setOpenCode,
  );

  useEffect(() => {
    if (stocks.length === 0) return;
    if (openCode === null || !stocks.some((s) => s.stockCode === openCode)) {
      setOpenCode(stocks[0].stockCode);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stocks]);

  const session =
    leadersQ.data && candidatesQ.data ? `domestic:${minChangeRate}` : null;
  const listed = useMembership(stocks.map((s) => s.stockCode), session);
  const lead = useMembership(leaders.map((s) => s.stockCode), session);
  const codes = stocks.map((s) => s.stockCode);
  const shown = new Set(codes);
  const flashOf = (code: string): Flash => {
    if (lead.entered.has(code)) return "promoted";
    if (lead.left.has(code) && shown.has(code)) return "demoted";
    return listed.entered.has(code) ? "new" : null;
  };

  const orderKey = codes.join(",");
  const rowsRef = useRef<HTMLDivElement>(null);
  const cardsRef = useRef<HTMLDivElement>(null);
  useSlideOnReorder(rowsRef, orderKey);
  useSlideOnReorder(cardsRef, orderKey);

  const open = (code: string) => {
    setOpenCode(code);
    mobile.show();
  };

  const list = (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-3 lg:px-3.5">
        <Header
          totalCount={stocks.length}
          fetchedAt={candidatesQ.dataUpdatedAt}
          loading={candidatesQ.isFetching}
        />
        <MarketToggle
          value={market}
          onChange={onMarket}
          trailing={<ChangeRateSelector value={minChangeRate} onChange={setMinChangeRate} />}
        />
      </div>

      {candidatesQ.isLoading || leadersQ.isLoading ? (
        <div className="space-y-3 p-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : stocks.length === 0 ? (
        <EmptyState message="조건을 통과한 후보가 없습니다" />
      ) : (
        <CandidatesList
          rowsRef={rowsRef}
          cardsRef={cardsRef}
          leaders={leaders}
          rest={rest}
          minChangeRate={minChangeRate}
          flashOf={flashOf}
          selectedCode={openCode}
          onOpen={open}
          reasons={reasons}
        />
      )}
    </div>
  );

  const opened = stocks.find((s) => s.stockCode === openCode);
  const reason = openCode ? reasons?.get(shortCode(openCode)) : undefined;
  const detail = me?.authenticated ? (
    <StockDetailPanel
      stockCode={openCode}
      preview={opened}
      onBack={mobile.hide}
      insight={<ReasonCard item={reason} member />}
    />
  ) : (
    <div className="flex flex-col gap-6">
      {opened && (
        <ReasonCard
          item={reason}
          member={false}
          head={
            <ReasonHead
              name={opened.stockName}
              code={shortCode(opened.stockCode)}
              rate={<ProfitText value={opened.priceChangeRate / 100} format={formatPct} />}
            />
          }
        />
      )}
      <LoginGate
        title="종목 상세"
        description="주도주 조건, 분봉, 일봉, 투자자 수급을 종목별로 봅니다. 로그인 후 확인할 수 있습니다."
      />
    </div>
  );

  return <ListDetail list={list} detail={detail} detailOpen={mobile.open} />;
}

function Header({
  totalCount,
  fetchedAt,
  loading,
}: {
  totalCount: number | undefined;
  fetchedAt: number;
  loading: boolean;
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

function CandidatesList({
  rowsRef,
  cardsRef,
  leaders,
  rest,
  minChangeRate,
  flashOf,
  selectedCode,
  onOpen,
  reasons,
}: {
  rowsRef: RefObject<HTMLDivElement>;
  cardsRef: RefObject<HTMLDivElement>;
  leaders: CandidateStockItem[];
  rest: CandidateStockItem[];
  minChangeRate: number;
  flashOf: (code: string) => Flash;
  selectedCode: string | null;
  onOpen: (stockCode: string) => void;
  reasons?: Map<string, InsightReasonItem>;
}) {
  const maxValue = Math.max(1, ...[...leaders, ...rest].map((s) => s.accumulatedTradingValue));
  const section = (list: CandidateStockItem[], Item: typeof Row | typeof Card) =>
    list.map((s, idx) => (
      <Item
        key={s.stockCode}
        stock={s}
        rank={idx + 1}
        valueRatio={s.accumulatedTradingValue / maxValue}
        flash={flashOf(s.stockCode)}
        isSelected={selectedCode === s.stockCode}
        onOpen={onOpen}
        reason={reasons?.get(shortCode(s.stockCode))}
      />
    ));
  const restHint = `거래대금 순 · 등락률 ${minChangeRate > 0 ? "+" : ""}${minChangeRate}% 이상`;
  const empty = <p className="px-4 py-6 text-center text-xs text-zinc-500">이 기준을 통과한 종목이 없습니다</p>;

  return (
    <>
      <div className="hidden lg:block" ref={rowsRef}>
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
      <div className="lg:hidden" ref={cardsRef}>
        {leaders.length > 0 && <GroupHeader label="주도주" count={leaders.length} guide />}
        {section(leaders, Card)}
        <GroupHeader label="후보" count={rest.length} hint={restHint} />
        {rest.length > 0 ? section(rest, Card) : empty}
      </div>
    </>
  );
}

type ItemProps = {
  stock: CandidateStockItem;
  rank: number;
  valueRatio: number;
  flash: Flash;
  isSelected: boolean;
  onOpen: (stockCode: string) => void;
  reason?: InsightReasonItem;
};

function Row({ stock, rank, valueRatio, flash, isSelected, onOpen, reason }: ItemProps) {
  return (
    <button
      type="button"
      data-stock-code={stock.stockCode}
      onClick={() => onOpen(stock.stockCode)}
      className={`grid w-full ${ROW_COLS} items-center gap-x-3 rounded-xl px-3.5 py-2.5 text-left transition-colors ${
        isSelected ? "bg-selected" : "hover:bg-zinc-850"
      } ${flash ? `leading-stock-${flash}` : ""}`}
    >
      <span className="num text-right text-xs text-zinc-500">{rank}</span>
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="truncate text-[13.5px] text-zinc-100">{stock.stockName}</span>
        <span className="num text-[11px] text-zinc-500">{shortCode(stock.stockCode)}</span>
      </span>
      <span className="flex flex-col items-end gap-1.5">
        <span className="num text-xs text-zinc-400">{formatKoreanMoney(stock.accumulatedTradingValue)}</span>
        <span className="relative h-0.5 w-full overflow-hidden rounded-full bg-zinc-850">
          <span className="absolute inset-y-0 right-0 rounded-full bg-zinc-500" style={{ width: `${valueRatio * 100}%` }} />
        </span>
      </span>
      <span className="num text-right text-[13.5px] text-zinc-100">
        <FlashOnChange value={stock.currentPrice} duration={1000}>
          <NumWon value={stock.currentPrice} />
        </FlashOnChange>
      </span>
      <span className="num text-right text-xs">
        <FlashOnChange value={stock.priceChangeRate} duration={1000}>
          <ProfitText value={stock.priceChangeRate / 100} format={formatPct} />
        </FlashOnChange>
      </span>
      <ReasonLine item={reason} className="col-span-4 col-start-2 mt-2" />
    </button>
  );
}

function Card({ stock, rank, flash, isSelected, onOpen, reason }: ItemProps) {
  return (
    <button
      type="button"
      data-stock-code={stock.stockCode}
      onClick={() => onOpen(stock.stockCode)}
      className={`flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2.5 text-left ${
        isSelected ? "bg-selected" : ""
      } ${flash ? `leading-stock-${flash}` : ""}`}
    >
      <span className="num w-4 shrink-0 text-right text-[11px] text-zinc-500">{rank}</span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="truncate text-[13.5px] text-zinc-100">{stock.stockName}</span>
        <span className="num text-[11px] text-zinc-500">{formatKoreanMoney(stock.accumulatedTradingValue)}</span>
        <ReasonLine item={reason} className="mt-1" />
      </span>
      <span className="flex shrink-0 flex-col items-end gap-0.5">
        <FlashOnChange value={stock.currentPrice} duration={1000}>
          <NumWon value={stock.currentPrice} className="num text-[13.5px] text-zinc-100" />
        </FlashOnChange>
        <FlashOnChange value={stock.priceChangeRate} duration={1000}>
          <ProfitText value={stock.priceChangeRate / 100} format={formatPct} className="num text-xs" />
        </FlashOnChange>
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
          <LeaderGuide market="domestic" />
        </span>
      )}
      {hint && <span className="text-xs text-zinc-500">{hint}</span>}
    </div>
  );
}
