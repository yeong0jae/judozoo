import { useEffect, useRef, useState } from "react";
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
import StockAvatar from "../components/common/StockAvatar";
import StockDetailPanel from "../components/common/StockDetailPanel";
import LoginGate from "../components/common/LoginGate";
import { useMe } from "../api/auth";
import ChangeRateSelector from "../components/common/ChangeRateSelector";
import { useArrowStockNav } from "../lib/useArrowStockNav";
import OverseasLeadingStocks from "./OverseasLeadingStocksPage";
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

/** 행 하이라이트 종류 — 목록 진입(amber)과 주도주 승격(violet)은 뜻이 다르다. */
type Flash = "new" | "promoted" | null;

/** 하이라이트가 남아 있는 시간(ms). CSS의 애니메이션 길이와 같아야 한다. */
const FLASH_MS = 8000;

/**
 * 방금 이 목록에 들어온 코드들. 첫 로드는 비운 채 지나간다 —
 * 화면을 열자마자 전부 반짝이면 "새로 들어왔다"는 뜻이 사라진다.
 */
function useArrivals(codes: string[]): Set<string> {
  const key = codes.join(",");
  const prevRef = useRef<Set<string> | null>(null);
  const [fresh, setFresh] = useState<Set<string>>(new Set());

  useEffect(() => {
    const current = new Set(key ? key.split(",") : []);
    const prev = prevRef.current;
    // 비교를 마치기 전에 갱신한다 — 갱신을 건너뛰면 다음 폴링이 같은 종목을 또 새것으로 본다
    prevRef.current = current;
    // 첫 로드(null)와 **빈 목록 다음**은 건너뛴다. 빈 상태와 비교하면 첫 응답 전체가
    // 새로 들어온 것으로 잡혀 목록이 통째로 반짝인다.
    if (prev === null || prev.size === 0) return;

    const justArrived = [...current].filter((c) => !prev.has(c));
    if (justArrived.length === 0) return;

    setFresh((p) => new Set([...p, ...justArrived]));
    const timers = justArrived.map((c) =>
      setTimeout(() => {
        setFresh((p) => {
          const next = new Set(p);
          next.delete(c);
          return next;
        });
      }, FLASH_MS),
    );
    return () => timers.forEach(clearTimeout);
  }, [key]);

  return fresh;
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

  const data = candidatesQ.data;
  // 위는 첫 화면과 같은 규칙으로 서버가 고른 주도주, 아래는 나머지 후보를 거래대금 순으로.
  // 등락률 임계값은 **아래 구간에만** 걸린다 — 위는 어떤 기준을 걸어두든 같은 답이어야 한다.
  const leaders = leadersQ.data ?? [];
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

  // 목록에 새로 든 종목과, 주도주 구간으로 올라온 종목을 따로 센다 — 뜻이 다른 사건이다
  const arrived = useArrivals(stocks.map((s) => s.stockCode));
  const promoted = useArrivals(leaders.map((s) => s.stockCode));
  const flashOf = (code: string): Flash =>
    promoted.has(code) ? "promoted" : arrived.has(code) ? "new" : null;

  const selector = (
    <ChangeRateSelector value={minChangeRate} onChange={setMinChangeRate} />
  );

  return (
    <div className="space-y-4">
      <Header
        totalCount={stocks.length}
        queriedAt={data?.queriedAt}
        loading={candidatesQ.isFetching}
      />

      {/* 토글+필터는 목록 컬럼 폭에 맞춰(필터가 리스트 오른쪽 끝에 정렬).
          선택기가 맨 위에 있지만 **걸리는 곳은 아래 "후보" 구간뿐**이다 — 주도주는 기준과 무관하다 */}
      <div className={openCode ? "grid grid-cols-1 lg:grid-cols-[9fr_11fr] gap-6" : ""}>
        <MarketToggle value={market} onChange={onMarket} trailing={selector} />
      </div>

      {/* 종목 선택 시 좌(목록) / 우(상세) 2분할, 선택 없으면 목록 전체 폭 */}
      <div
        className={
          openCode
            ? "grid grid-cols-1 lg:grid-cols-[9fr_11fr] gap-6 items-start"
            : ""
        }
      >
        <section>
          {candidatesQ.isLoading || leadersQ.isLoading ? (
            <div className="p-6 space-y-3">
              {Array.from({ length: 5 }).map((_, i) => (
                <Skeleton key={i} className="h-10 w-full" />
              ))}
            </div>
          ) : stocks.length === 0 ? (
            <EmptyState message="조건을 통과한 후보가 없습니다" />
          ) : (
            <>
              <CandidatesTable
                leaders={leaders}
                rest={rest}
                flashOf={flashOf}
                selectedCode={openCode}
                onOpen={(code) => setOpenCode(code)}
              />
              <CandidatesCards
                leaders={leaders}
                rest={rest}
                flashOf={flashOf}
                selectedCode={openCode}
                onOpen={(code) => setOpenCode(code)}
              />
            </>
          )}
        </section>

        {openCode && (
          <aside className="lg:sticky lg:top-6">
            {me?.authenticated ? (
              <StockDetailPanel stockCode={openCode} defaultTab="detail" />
            ) : (
              <LoginGate
                title="종목 상세"
                description="필터 평가, 분봉, 일봉, 투자자 수급을 종목별로 봅니다. 로그인하면 확인할 수 있습니다."
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
// Candidates table
// ============================================================

/** 종목의 대표 테마 칩. 전체 테마가 더 많으면 "+N" 표기. */
function CandidatesTable({
  leaders,
  rest,
  flashOf,
  selectedCode,
  onOpen,
}: {
  leaders: CandidateStockItem[];
  rest: CandidateStockItem[];
  flashOf: (code: string) => Flash;
  selectedCode: string | null;
  onOpen: (stockCode: string) => void;
}) {
  /** 한 구간의 행들. 마지막 줄엔 실선을 빼 둔다 — 다음 구간 머리가 이미 경계를 만든다. */
  const section = (list: CandidateStockItem[]) =>
    list.map((s, idx) => (
      <Row
        key={s.stockCode}
        stock={s}
        rank={idx + 1}
        line={idx !== list.length - 1}
        flash={flashOf(s.stockCode)}
        isSelected={selectedCode === s.stockCode}
        onOpen={onOpen}
      />
    ));

  return (
    <table className="hidden md:table w-full text-xs border-separate border-spacing-y-0">
      <thead className="text-zinc-500 text-xs">
        <tr>
          <th className="pl-4 py-2.5 text-left whitespace-nowrap font-medium">순위</th>
          <th className="px-2 py-2.5 text-left font-medium">종목</th>
          <th className="px-4 py-2.5 text-right font-medium">현재가</th>
          <th className="px-4 py-2.5 text-right font-medium">등락률</th>
          <th className="px-4 py-2.5 text-right font-medium">거래대금</th>
        </tr>
      </thead>
      <tbody>
        {leaders.length > 0 && <GroupHeader label="주도주" />}
        {section(leaders)}
        {/* 통과 종목이 없어도 머리는 그린다 — 없으면 "주도주만 있는 화면"으로 보여 기준이 걸렸다는 걸 알 수 없다 */}
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

/** 목록 한 줄. 코드는 이름 오른쪽에 — 아래 줄로 내리면 행이 두 줄 높이가 된다. */
function Row({
  stock,
  rank,
  line,
  flash,
  isSelected,
  onOpen,
}: {
  stock: CandidateStockItem;
  rank: number;
  line: boolean;
  flash: Flash;
  isSelected: boolean;
  onOpen: (stockCode: string) => void;
}) {
  const code = shortCode(stock.stockCode);
  return (
    <tr
      data-stock-code={stock.stockCode}
      className={`cursor-pointer transition-colors hover:[&>td]:bg-zinc-850 [&>td:first-child]:rounded-l-xl [&>td:last-child]:rounded-r-xl ${
        line ? "[&>td]:border-b [&>td]:border-zinc-800/60" : ""
      } ${flash ? `leading-stock-${flash}` : ""} ${isSelected ? "[&>td]:bg-selected" : ""}`}
      onClick={() => onOpen(stock.stockCode)}
    >
      <td className="pl-4 py-3.5 text-zinc-500 num w-10">{rank}</td>
      <td className="px-2 py-3.5">
        <div className="flex items-center gap-2.5 min-w-0">
          <StockAvatar name={stock.stockName} code={code} size={28} />
          <span className="font-semibold text-zinc-100 truncate">{stock.stockName}</span>
          <span className="text-[11.5px] text-zinc-500 num shrink-0">{code}</span>
        </div>
      </td>
      <td className="px-4 py-3.5 text-right num font-medium text-zinc-100">
        {/* 가격 변동 flash — 상승 빨강, 하락 파랑 (한국 거래소 관행) */}
        <FlashOnChange value={stock.currentPrice} duration={1000}>
          <NumWon value={stock.currentPrice} />
        </FlashOnChange>
      </td>
      <td className="px-4 py-3.5 text-right num font-medium">
        {/* 키움은 등락률을 이미 % 단위로 주고, formatPct는 분수→% 변환이라 /100 해서 맞춤 */}
        <FlashOnChange value={stock.priceChangeRate} duration={1000}>
          <ProfitText value={stock.priceChangeRate / 100} format={formatPct} />
        </FlashOnChange>
      </td>
      <td className="px-4 py-3.5 text-right num text-zinc-400">
        {formatKoreanMoney(stock.accumulatedTradingValue)}
      </td>
    </tr>
  );
}

/** 구간 머리 — 표의 층을 가르는 자리라 행 글씨보다 크게 세운다. */
function GroupHeader({
  label,
  hint,
}: {
  label: string;
  hint?: string;
}) {
  return (
    <tr>
      {/* 표 좌측 끝(순위 컬럼 자리)에서 라벨 시작 — 1·2·3 번호 컬럼과 좌측 정렬 일치 */}
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
// Candidates cards (모바일)
// ============================================================

function CandidatesCards({
  leaders,
  rest,
  flashOf,
  selectedCode,
  onOpen,
}: {
  leaders: CandidateStockItem[];
  rest: CandidateStockItem[];
  flashOf: (code: string) => Flash;
  selectedCode: string | null;
  onOpen: (stockCode: string) => void;
}) {
  const section = (list: CandidateStockItem[]) =>
    list.map((s, idx) => (
      <Card
        key={s.stockCode}
        stock={s}
        rank={idx + 1}
        line={idx !== list.length - 1}
        flash={flashOf(s.stockCode)}
        isSelected={selectedCode === s.stockCode}
        onOpen={onOpen}
      />
    ));

  return (
    <div className="md:hidden">
      {leaders.length > 0 && <CardGroupHeader label="주도주" />}
      {section(leaders)}
      {/* 통과 종목이 없어도 머리는 그린다 */}
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
  flash,
  isSelected,
  onOpen,
}: {
  stock: CandidateStockItem;
  rank: number;
  line: boolean;
  flash: Flash;
  isSelected: boolean;
  onOpen: (stockCode: string) => void;
}) {
  const code = shortCode(stock.stockCode);
  return (
    <div
      data-stock-code={stock.stockCode}
      className={`rounded-xl px-4 py-3.5 flex flex-col gap-1 cursor-pointer ${
        line ? "border-b border-zinc-800/60" : ""
      } ${flash ? `leading-stock-${flash}` : ""} ${isSelected ? "bg-selected" : ""}`}
      onClick={() => onOpen(stock.stockCode)}
    >
      {/* 1행: 순위 · 아바타 · 종목명 · 현재가 */}
      <div className="flex items-center gap-2">
        <span className="text-zinc-500 text-xs num w-4 shrink-0">{rank}</span>
        <StockAvatar name={stock.stockName} code={code} size={28} />
        <span className="font-semibold truncate flex-1 min-w-0">{stock.stockName}</span>
        <FlashOnChange value={stock.currentPrice} duration={1000}>
          <NumWon value={stock.currentPrice} className="num shrink-0 font-medium" />
        </FlashOnChange>
      </div>
      {/* 2행: 코드·거래대금 · 등락률 — 폰은 한 줄에 다 넣으면 이름이 잘려 두 줄로 둔다 */}
      <div className="flex items-center gap-2 pl-[3.25rem]">
        <span className="text-xs text-zinc-500 num whitespace-nowrap truncate flex-1 min-w-0">
          {code} · {formatKoreanMoney(stock.accumulatedTradingValue)}
        </span>
        <FlashOnChange value={stock.priceChangeRate} duration={1000}>
          <ProfitText
            value={stock.priceChangeRate / 100}
            format={formatPct}
            className="num text-xs shrink-0"
          />
        </FlashOnChange>
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
