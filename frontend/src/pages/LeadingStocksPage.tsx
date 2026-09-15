import { Fragment, useEffect, useRef, useState } from "react";
import { useLeadingStockCandidates } from "../api/queries";
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
import { ALWAYS_INCLUDED_LABEL, ALWAYS_INCLUDED_RANKS } from "../lib/leadingStock";

/**
 * 키움 마스터 코드 — 거래 ID로는 6자리 단축코드만 사용.
 * "009150_AL" 같이 거래소 접미사가 붙어 오면 "_" 앞쪽으로 잘라낸다.
 */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
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
  const candidatesQ = useLeadingStockCandidates(minChangeRate);
  const [openCode, setOpenCode] = useState<string | null>(null);
  // ↑/↓ 방향키로 선택 종목 이동
  useArrowStockNav(
    (candidatesQ.data?.stocks ?? []).map((s) => s.stockCode),
    openCode,
    setOpenCode,
  );

  const data = candidatesQ.data;
  const stocks = data?.stocks ?? [];

  // 페이지 진입 시 첫 종목 기본 선택, 선택 종목이 리스트에서 사라지면 다시 첫 종목으로
  useEffect(() => {
    if (stocks.length === 0) return;
    if (openCode === null || !stocks.some((s) => s.stockCode === openCode)) {
      setOpenCode(stocks[0].stockCode);
    }
    // openCode를 deps에 넣지 않음 — 사용자 클릭 시마다 재실행되는 걸 막기 위해 stocks(데이터)에만 반응
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stocks]);

  // 새로 진입한 종목 추적 — 행 8초 하이라이트용. 첫 로드는 마킹 제외.
  const prevCodesRef = useRef<Set<string>>(new Set());
  const [newCodes, setNewCodes] = useState<Set<string>>(new Set());
  useEffect(() => {
    const current = new Set(stocks.map((s) => s.stockCode));
    if (prevCodesRef.current.size > 0) {
      const justArrived = [...current].filter(
        (c) => !prevCodesRef.current.has(c),
      );
      if (justArrived.length > 0) {
        setNewCodes((prev) => {
          const next = new Set(prev);
          justArrived.forEach((c) => next.add(c));
          return next;
        });
        const timeouts = justArrived.map((c) =>
          setTimeout(() => {
            setNewCodes((prev) => {
              const next = new Set(prev);
              next.delete(c);
              return next;
            });
          }, 8000),
        );
        // 컴포넌트 언마운트 시 타이머 정리
        return () => timeouts.forEach(clearTimeout);
      }
    }
    prevCodesRef.current = current;
  }, [stocks]);

  return (
    <div className="space-y-4">
      <Header
        totalCount={data?.totalCount}
        queriedAt={data?.queriedAt}
        loading={candidatesQ.isFetching}
      />

      {/* 토글+필터는 목록 컬럼 폭에 맞춰(필터가 리스트 오른쪽 끝에 정렬) */}
      <div className={openCode ? "grid grid-cols-1 lg:grid-cols-[9fr_11fr] gap-6" : ""}>
        <MarketToggle
          value={market}
          onChange={onMarket}
          trailing={<ChangeRateSelector value={minChangeRate} onChange={setMinChangeRate} />}
        />
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
          {candidatesQ.isLoading ? (
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
                stocks={stocks}
                newCodes={newCodes}
                selectedCode={openCode}
                onOpen={(code) => setOpenCode(code)}
              />
              <CandidatesCards
                stocks={stocks}
                newCodes={newCodes}
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
      title="주도주 필터"
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
  stocks,
  newCodes,
  selectedCode,
  onOpen,
}: {
  stocks: CandidateStockItem[];
  newCodes: Set<string>;
  selectedCode: string | null;
  onOpen: (stockCode: string) => void;
}) {
  return (
    <table className="hidden md:table w-full text-xs border-separate border-spacing-y-1">
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
        {stocks.length > 0 && (
          <GroupHeader label={ALWAYS_INCLUDED_LABEL} />
        )}
        {stocks.map((s, idx) => {
          const code = shortCode(s.stockCode);
          const isNew = newCodes.has(s.stockCode);
          const isSelected = selectedCode === s.stockCode;
          return (
            <Fragment key={s.stockCode}>
              {idx === ALWAYS_INCLUDED_RANKS && <GroupHeader label="주도주 후보" />}
              <tr
                data-stock-code={s.stockCode}
                className={`cursor-pointer transition-colors hover:[&>td]:bg-zinc-850 [&>td:first-child]:rounded-l-xl [&>td:last-child]:rounded-r-xl ${
                  isNew ? "leading-stock-new" : ""
                } ${isSelected ? "[&>td]:bg-selected" : ""}`}
                onClick={() => onOpen(s.stockCode)}
              >
              <td className="pl-4 py-3.5 text-zinc-500 num w-10">{s.rank}</td>
              <td className="px-2 py-3.5">
                <div className="flex items-center gap-3">
                  <StockAvatar name={s.stockName} code={code} />
                  <div className="min-w-0">
                    <span className="font-semibold text-zinc-100">{s.stockName}</span>
                    <div className="text-xs text-zinc-500 num mt-0.5">{code}</div>
                  </div>
                </div>
              </td>
              <td className="px-4 py-3.5 text-right num font-medium text-zinc-100">
                {/* 가격 변동 flash — 상승 빨강, 하락 파랑 (한국 거래소 관행) */}
                <FlashOnChange value={s.currentPrice} duration={1000}>
                  <NumWon value={s.currentPrice} />
                </FlashOnChange>
              </td>
              <td className="px-4 py-3.5 text-right num font-medium">
                {/* 키움은 등락률을 이미 % 단위로 주고, formatPct는 분수→% 변환이라 /100 해서 맞춤 */}
                <FlashOnChange value={s.priceChangeRate} duration={1000}>
                  <ProfitText
                    value={s.priceChangeRate / 100}
                    format={formatPct}
                  />
                </FlashOnChange>
              </td>
              <td className="px-4 py-3.5 text-right num text-zinc-400">
                {formatKoreanMoney(s.accumulatedTradingValue)}
              </td>
            </tr>
            </Fragment>
          );
        })}
      </tbody>
    </table>
  );
}

function GroupHeader({ label, hint }: { label: string; hint?: string }) {
  return (
    <tr aria-hidden>
      {/* 표 좌측 끝(순위 컬럼 자리)에서 라벨 시작 — 1·2·3 번호 컬럼과 좌측 정렬 일치 */}
      <td colSpan={5} className="px-4 py-2.5">
        <span className="text-xs font-semibold text-zinc-400">{label}</span>
        {hint && (
          <span className="ml-2 text-xs text-zinc-500 font-normal">{hint}</span>
        )}
      </td>
    </tr>
  );
}

// ============================================================
// Candidates cards (모바일)
// ============================================================

function CandidatesCards({
  stocks,
  newCodes,
  selectedCode,
  onOpen,
}: {
  stocks: CandidateStockItem[];
  newCodes: Set<string>;
  selectedCode: string | null;
  onOpen: (stockCode: string) => void;
}) {
  return (
    <div className="md:hidden space-y-1">
      {stocks.map((s, idx) => {
        const code = shortCode(s.stockCode);
        const isNew = newCodes.has(s.stockCode);
        const isSelected = selectedCode === s.stockCode;
        return (
          <Fragment key={s.stockCode}>
            {idx === 0 && <CardGroupHeader label={ALWAYS_INCLUDED_LABEL} />}
            {idx === ALWAYS_INCLUDED_RANKS && <CardGroupHeader label="주도주 후보" />}
            <div
              data-stock-code={s.stockCode}
              className={`rounded-xl px-4 py-3.5 flex flex-col gap-1 cursor-pointer ${
                isNew ? "leading-stock-new" : ""
              } ${isSelected ? "bg-selected" : ""}`}
              onClick={() => onOpen(s.stockCode)}
            >
              {/* 1행: 아바타 · 종목명 · 현재가 */}
              <div className="flex items-center gap-2">
                <span className="text-zinc-500 text-xs num w-4 shrink-0">{s.rank}</span>
                <StockAvatar name={s.stockName} code={code} size={26} />
                <span className="font-semibold truncate flex-1 min-w-0">
                  {s.stockName}
                </span>
                <FlashOnChange value={s.currentPrice} duration={1000}>
                  <NumWon value={s.currentPrice} className="num shrink-0 font-medium" />
                </FlashOnChange>
              </div>
              {/* 2행: 코드·거래대금 · 등락률 */}
              <div className="flex items-center gap-2 pl-[3.25rem]">
                <span className="text-xs text-zinc-500 num whitespace-nowrap truncate flex-1 min-w-0">
                  {code} · {formatKoreanMoney(s.accumulatedTradingValue)}
                </span>
                <FlashOnChange value={s.priceChangeRate} duration={1000}>
                  <ProfitText
                    value={s.priceChangeRate / 100}
                    format={formatPct}
                    className="num text-xs shrink-0"
                  />
                </FlashOnChange>
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
