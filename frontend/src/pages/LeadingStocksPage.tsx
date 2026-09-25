import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { useLeadingStockCandidates, useLeadingStockLeaders } from "../api/queries";
import type { CandidateStockItem } from "../types";
import { formatKoreanMoney, formatPct, formatRelative } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import { useMinChangeRate } from "../lib/changeRate";
import PageHeader from "../components/layout/PageHeader";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import FlashOnChange from "../components/common/FlashOnChange";
import NumWon from "../components/common/NumWon";
import StockDetailPanel from "../components/common/StockDetailPanel";
import LoginGate from "../components/common/LoginGate";
import { useMe } from "../api/auth";
import ChangeRateSelector from "../components/common/ChangeRateSelector";
import { useArrowStockNav } from "../lib/useArrowStockNav";
import OverseasLeadingStocks from "./OverseasLeadingStocksPage";
import ListDetail, { useMobileDetail } from "../components/layout/ListDetail";
import MarketToggle from "../components/common/MarketToggle";
import { loadMarket, rememberMarket, type StockMarket } from "../lib/stockMarket";

/**
 * 키움 마스터 코드 — 거래 ID로는 6자리 단축코드만 사용.
 * "009150_AL" 같이 거래소 접미사가 붙어 오면 "_" 앞쪽으로 잘라낸다.
 */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

/** 행 하이라이트 종류 — 목록 진입(amber) · 주도주 승격(violet) · 강등(회색). */
type Flash = "new" | "promoted" | "demoted" | null;

/** 하이라이트가 남아 있는 시간(ms). CSS의 애니메이션 길이와 같아야 한다. */
const FLASH_MS = 4000;

/**
 * 방금 이 목록에 들어온 코드들.
 *
 * `session`은 "이 목록을 같은 조건으로 계속 보고 있다"는 표시다. null이면 아직 볼 준비가
 * 안 된 것이고, 값이 바뀌면 이전 기억을 버린다. 이게 없으면 목록이 **한 번에 채워지지 않는
 * 순간마다** 오탐이 난다 —
 *
 * - 두 조회(주도주·후보)가 따로 도착하면, 먼저 온 쪽만 담긴 목록과 비교해 나머지가 전부 새것이 된다
 * - 등락률을 바꾸면 조회 키가 달라져 목록이 새로 오는데, 옛 목록과 비교하면 늘어난 만큼 전부 반짝인다
 *
 * 첫 비교는 언제나 건너뛴다. 화면을 열자마자 전부 반짝이면 "새로 들어왔다"는 뜻이 사라진다.
 */
function useMembership(
  codes: string[],
  session: string | null,
): { entered: Set<string>; left: Set<string> } {
  const key = codes.join(",");
  const prevRef = useRef<{ session: string; codes: Set<string> } | null>(null);
  const [entered, setEntered] = useState<Set<string>>(new Set());
  const [left, setLeft] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (session === null) return; // 아직 볼 준비가 안 됐다

    const current = new Set(key ? key.split(",") : []);
    const prev = prevRef.current;
    // 비교를 마치기 전에 갱신한다 — 갱신을 건너뛰면 다음 폴링이 같은 종목을 또 새것으로 본다
    prevRef.current = { session, codes: current };
    // 기억이 없거나(첫 비교), 다른 조건에서 본 목록이거나, 직전이 빈 목록이면 비교하지 않는다
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

/**
 * 순서가 바뀐 줄을 새 자리로 미끄러뜨린다(FLIP).
 *
 * 직전 그림의 위치를 기억해 뒀다가, 새로 그려진 직후 **그 차이만큼 되돌려 놓고** 0으로 푼다.
 * 애니메이션이 없으면 다음 응답에서 줄이 갑자기 다른 자리에 나타나, 무엇이 어디로 갔는지 알 수 없다.
 */
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

/**
 * 주도주 후보 — 국내/해외 토글로 전환. 안 보이는 쪽은 언마운트되어 폴링이 멈춘다.
 */
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
  // 목록은 공개, 종목 상세는 로그인 뒤다.
  const { data: me } = useMe();
  // 당일 등락률 임계값(%) — 헤더 티커와 공유한다(같은 값이어야 조회가 합쳐진다).
  const [minChangeRate, setMinChangeRate] = useMinChangeRate();
  const leadersQ = useLeadingStockLeaders();
  const candidatesQ = useLeadingStockCandidates(minChangeRate);
  const [openCode, setOpenCode] = useState<string | null>(null);
  const mobile = useMobileDetail();

  const data = candidatesQ.data;
  // 위는 첫 화면과 같은 규칙으로 서버가 고른 주도주, 아래는 나머지 후보를 거래대금 순으로.
  // 등락률 임계값은 **아래 구간에만** 걸린다 — 위는 어떤 기준을 걸어두든 같은 답이어야 한다.
  const leaders = leadersQ.data?.leaders ?? [];
  const leaderCodes = new Set(leaders.map((s) => s.stockCode));
  const rest = (data?.stocks ?? []).filter((s) => !leaderCodes.has(s.stockCode));
  const stocks = [...leaders, ...rest];

  // ↑/↓ 방향키로 선택 종목 이동
  useArrowStockNav(
    stocks.map((s) => s.stockCode),
    openCode,
    setOpenCode,
  );

  // 페이지 진입 시 첫 종목 기본 선택, 선택 종목이 리스트에서 사라지면 다시 첫 종목으로
  useEffect(() => {
    if (stocks.length === 0) return;
    if (openCode === null || !stocks.some((s) => s.stockCode === openCode)) {
      setOpenCode(stocks[0].stockCode);
    }
    // openCode를 deps에 넣지 않음 — 사용자 클릭 시마다 재실행되는 걸 막기 위해 stocks(데이터)에만 반응
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stocks]);

  // 목록에 새로 든 종목과, 주도주 구간으로 올라온 종목을 따로 센다 — 뜻이 다른 사건이다.
  // **두 조회가 다 도착해야** 비교를 시작한다. 한쪽만 온 목록과 비교하면 나머지가 전부 새것이 된다.
  const session =
    leadersQ.data && candidatesQ.data ? `domestic:${minChangeRate}` : null;
  const listed = useMembership(stocks.map((s) => s.stockCode), session);
  const lead = useMembership(leaders.map((s) => s.stockCode), session);
  const codes = stocks.map((s) => s.stockCode);
  const shown = new Set(codes);
  const flashOf = (code: string): Flash => {
    if (lead.entered.has(code)) return "promoted";
    // 주도주에서 빠졌어도 목록에 남아 있을 때만 강등이다 — 아예 나간 종목은 그릴 자리가 없다
    if (lead.left.has(code) && shown.has(code)) return "demoted";
    return listed.entered.has(code) ? "new" : null;
  };

  // 순서가 바뀌면 줄이 새 자리로 미끄러진다 (데스크톱 줄·모바일 줄 각각)
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
          queriedAt={data?.queriedAt}
          loading={candidatesQ.isFetching}
        />
        {/* 선택기가 맨 위에 있지만 **걸리는 곳은 아래 "후보" 구간뿐**이다 — 주도주는 기준과 무관하다 */}
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
        />
      )}
    </div>
  );

  const detail = me?.authenticated ? (
    <StockDetailPanel stockCode={openCode} onBack={mobile.hide} />
  ) : (
    <LoginGate
      title="종목 상세"
      description="주도주 조건, 분봉, 일봉, 투자자 수급을 종목별로 봅니다. 로그인 후 확인할 수 있습니다."
    />
  );

  return <ListDetail list={list} detail={detail} detailOpen={mobile.open} />;
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
    <PageHeader
      title="오늘의 주도주"
      count={totalCount}
      queriedAt={queriedAt ? formatRelative(queriedAt) : undefined}
      loading={loading}
    />
  );
}

// ============================================================
// 목록 — 넓으면 열 맞춘 줄, 좁으면 두 줄 카드
// ============================================================

/** 데스크톱 줄의 열 — 순위 · 종목 · 거래대금 · 현재가 · 등락률. 머리와 줄이 같은 값을 쓴다. */
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
}: {
  rowsRef: RefObject<HTMLDivElement>;
  cardsRef: RefObject<HTMLDivElement>;
  leaders: CandidateStockItem[];
  rest: CandidateStockItem[];
  minChangeRate: number;
  flashOf: (code: string) => Flash;
  selectedCode: string | null;
  onOpen: (stockCode: string) => void;
}) {
  // 막대는 목록 안에서 가장 큰 거래대금이 꽉 찬 폭이다
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
      />
    ));
  const restHint = `거래대금 순 · 등락률 ${minChangeRate > 0 ? "+" : ""}${minChangeRate}% 이상`;
  // 통과 종목이 없어도 머리는 그린다 — 없으면 "주도주만 있는 화면"으로 보여 기준이 걸렸다는 걸 알 수 없다
  const empty = <p className="px-4 py-6 text-center text-xs text-zinc-500">이 기준을 통과한 종목이 없습니다</p>;

  return (
    <>
      <div className="hidden lg:block" ref={rowsRef}>
        <div className={`grid ${ROW_COLS} gap-x-3 px-3.5 pb-1.5 text-[11px] text-zinc-500`}>
          <span />
          <span>종목</span>
          <span className="text-right">거래대금</span>
          <span className="text-right">현재가</span>
          <span className="text-right">등락률</span>
        </div>
        {leaders.length > 0 && <GroupHeader label="주도주" count={leaders.length} />}
        {section(leaders, Row)}
        <GroupHeader label="후보" count={rest.length} hint={restHint} />
        {rest.length > 0 ? section(rest, Row) : empty}
      </div>
      <div className="lg:hidden" ref={cardsRef}>
        {leaders.length > 0 && <GroupHeader label="주도주" count={leaders.length} />}
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
  /** 목록 최대 거래대금 대비 비율(0~1) — 데스크톱 줄의 막대 */
  valueRatio: number;
  flash: Flash;
  isSelected: boolean;
  onOpen: (stockCode: string) => void;
};

/** 데스크톱 한 줄 — 코드는 이름 아래. */
function Row({ stock, rank, valueRatio, flash, isSelected, onOpen }: ItemProps) {
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
        <span className="truncate text-sm text-zinc-100">{stock.stockName}</span>
        <span className="num text-[11px] text-zinc-500">{shortCode(stock.stockCode)}</span>
      </span>
      <span className="flex flex-col items-end gap-1.5">
        <span className="num text-xs text-zinc-400">{formatKoreanMoney(stock.accumulatedTradingValue)}</span>
        <span className="relative h-1 w-full overflow-hidden rounded-full bg-zinc-850">
          <span className="absolute inset-y-0 right-0 rounded-full bg-zinc-500" style={{ width: `${valueRatio * 100}%` }} />
        </span>
      </span>
      <span className="num text-right text-sm text-zinc-100">
        {/* 가격 변동 flash — 상승 빨강, 하락 파랑 (한국 거래소 관행) */}
        <FlashOnChange value={stock.currentPrice} duration={1000}>
          <NumWon value={stock.currentPrice} />
        </FlashOnChange>
      </span>
      <span className="num text-right text-xs">
        {/* 키움은 등락률을 이미 % 단위로 주고, formatPct는 분수→% 변환이라 /100 해서 맞춤 */}
        <FlashOnChange value={stock.priceChangeRate} duration={1000}>
          <ProfitText value={stock.priceChangeRate / 100} format={formatPct} />
        </FlashOnChange>
      </span>
    </button>
  );
}

/** 모바일 한 줄 — 왼쪽 이름·거래대금, 오른쪽 현재가·등락률. */
function Card({ stock, rank, flash, isSelected, onOpen }: ItemProps) {
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
        <span className="truncate text-sm text-zinc-100">{stock.stockName}</span>
        <span className="num text-[11px] text-zinc-500">{formatKoreanMoney(stock.accumulatedTradingValue)}</span>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-0.5">
        <FlashOnChange value={stock.currentPrice} duration={1000}>
          <NumWon value={stock.currentPrice} className="num text-sm text-zinc-100" />
        </FlashOnChange>
        <FlashOnChange value={stock.priceChangeRate} duration={1000}>
          <ProfitText value={stock.priceChangeRate / 100} format={formatPct} className="num text-xs" />
        </FlashOnChange>
      </span>
    </button>
  );
}

/** 구간 머리 — 목록의 층을 가르는 자리라 줄 글씨보다 크게 세운다. */
function GroupHeader({ label, count, hint }: { label: string; count: number; hint?: string }) {
  return (
    <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 px-2.5 pb-1.5 pt-4 lg:px-3.5">
      <span className="text-[15px] font-bold tracking-tight text-zinc-100">{label}</span>
      <span className="num text-xs text-zinc-400">{count}</span>
      {hint && <span className="text-xs text-zinc-500">{hint}</span>}
    </div>
  );
}
