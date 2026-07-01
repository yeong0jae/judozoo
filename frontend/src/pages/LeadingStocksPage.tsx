import { Fragment, useEffect, useRef, useState } from "react";
import { useLeadingStockCandidates } from "../api/queries";
import type { CandidateStockItem } from "../types";
import { formatKoreanMoney, formatPct, formatRelative } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import FlashOnChange from "../components/common/FlashOnChange";
import NumWon from "../components/common/NumWon";
import StockAvatar from "../components/common/StockAvatar";
import ThemeChips from "../components/common/ThemeChips";
import StockDetailPanel from "../components/common/StockDetailPanel";
import ChangeRateSelector, {
  CHANGE_RATE_OPTIONS,
} from "../components/common/ChangeRateSelector";
import { useArrowStockNav } from "../lib/useArrowStockNav";
import OverseasLeadingStocks from "./OverseasLeadingStocksPage";
import MarketToggle, { type StockMarket } from "../components/common/MarketToggle";

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
  const [market, setMarket] = useState<StockMarket>(() => {
    const saved = localStorage.getItem(MARKET_KEY);
    return saved === "overseas" ? "overseas" : "domestic";
  });
  useEffect(() => {
    localStorage.setItem(MARKET_KEY, market);
  }, [market]);

  const toggle = <MarketToggle value={market} onChange={setMarket} />;

  return market === "domestic" ? (
    <DomesticLeadingStocks toggle={toggle} />
  ) : (
    <OverseasLeadingStocks toggle={toggle} />
  );
}

const MARKET_KEY = "leadingStock.market";

function DomesticLeadingStocks({ toggle }: { toggle: React.ReactNode }) {
  // 당일 등락률 임계값(%) — 사용자 선택. 새로고침해도 유지되도록 localStorage에 보관, 기본 7%.
  const [minChangeRate, setMinChangeRate] = useState(() => {
    const raw = localStorage.getItem(MIN_CHANGE_RATE_KEY);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : 7;
  });
  useEffect(() => {
    localStorage.setItem(MIN_CHANGE_RATE_KEY, String(minChangeRate));
  }, [minChangeRate]);
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

      {toggle}

      {/* 종목 선택 시 좌(목록) / 우(상세) 2분할, 선택 없으면 목록 전체 폭 */}
      <div
        className={
          openCode
            ? "grid grid-cols-1 lg:grid-cols-[9fr_11fr] gap-6 items-start"
            : ""
        }
      >
        <section>
          {/* 등락률 임계값 선택 — 리스트 우측 상단 */}
          <div className="flex justify-end px-4 py-2.5 border-b border-white/[0.04]">
            <ChangeRateSelector value={minChangeRate} onChange={setMinChangeRate} />
          </div>
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
            <StockDetailPanel stockCode={openCode} defaultTab="detail" />
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
    <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
      <div>
        <h2 className="text-xl font-bold flex items-center gap-2">
          주도주 후보
          {/* 라이브 인디케이터: 폴링 중엔 ping, 대기 시 pulse — 갱신 중임을 일정하게 시그널 */}
          <span
            className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
              loading ? "animate-ping" : "animate-pulse"
            }`}
            aria-label={loading ? "갱신 중" : "대기"}
          />
        </h2>
        <p className="text-xs text-zinc-500 mt-0.5">
          거래대금 상위 + 당일 등락률 필터 통과 종목 · 5초 자동 갱신
        </p>
      </div>
      <div className="text-xs text-zinc-500 flex items-center gap-2">
        {queriedAt && <span>조회 {formatRelative(queriedAt)}</span>}
        {typeof totalCount === "number" && (
          <span className="text-zinc-300 font-medium">{totalCount}건</span>
        )}
      </div>
    </div>
  );
}

const MIN_CHANGE_RATE_KEY = "leadingStock.minChangeRate";

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
          <GroupHeader label="거래대금 1, 2, 3위" />
        )}
        {stocks.map((s, idx) => {
          const code = shortCode(s.stockCode);
          const isNew = newCodes.has(s.stockCode);
          const isSelected = selectedCode === s.stockCode;
          return (
            <Fragment key={s.stockCode}>
              {idx === 3 && <GroupHeader label="주도주 후보" />}
              <tr
                data-stock-code={s.stockCode}
                className={`cursor-pointer transition-colors hover:[&>td]:bg-white/[0.03] [&>td:first-child]:rounded-l-xl [&>td:last-child]:rounded-r-xl ${
                  isNew ? "leading-stock-new" : ""
                } ${isSelected ? "[&>td]:bg-emerald-900" : ""}`}
                onClick={() => onOpen(s.stockCode)}
              >
              <td className="pl-4 py-3.5 text-zinc-500 num w-10">{s.rank}</td>
              <td className="px-2 py-3.5">
                <div className="flex items-center gap-3">
                  <StockAvatar name={s.stockName} code={code} />
                  <div className="min-w-0">
                    <div className="flex items-center flex-wrap gap-1.5">
                      <span className="font-semibold text-zinc-100">{s.stockName}</span>
                      <ThemeChips themes={s.themes} themeCount={s.themeCount} />
                    </div>
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
            {idx === 0 && <CardGroupHeader label="거래대금 1, 2, 3위" />}
            {idx === 3 && <CardGroupHeader label="주도주 후보" />}
            <div
              data-stock-code={s.stockCode}
              className={`rounded-xl px-4 py-3.5 flex flex-col gap-1 cursor-pointer ${
                isNew ? "leading-stock-new" : ""
              } ${isSelected ? "bg-emerald-900" : ""}`}
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
              {/* 3행: 테마 칩 */}
              <div className="pl-7">
                <ThemeChips themes={s.themes} themeCount={s.themeCount} />
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
