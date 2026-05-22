import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  useInvestorTrend,
  useLeadingStockCandidates,
  useLeadingStockDetail,
} from "../api/queries";
import type {
  CandidateStockItem,
  FilterResultItem,
  InvestorTrendDay,
} from "../types";
import {
  formatKoreanMoney,
  formatPct,
  formatPrice,
  formatRelative,
} from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import FlashOnChange from "../components/common/FlashOnChange";

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
    <div className="space-y-6">
      <Header
        totalCount={data?.totalCount}
        queriedAt={data?.queriedAt}
        loading={candidatesQ.isFetching}
      />

      {/* 종목 선택 시 좌(목록) / 우(상세) 2분할, 선택 없으면 목록 전체 폭 */}
      <div
        className={
          openCode
            ? "grid grid-cols-1 lg:grid-cols-2 gap-6 items-start"
            : ""
        }
      >
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
              newCodes={newCodes}
              selectedCode={openCode}
              onOpen={(code) => setOpenCode(code)}
            />
          )}
        </section>

        {openCode && (
          <aside className="lg:sticky lg:top-6">
            <DetailPanel
              stockCode={openCode}
              onClose={() => setOpenCode(null)}
            />
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
    <div className="flex items-baseline justify-between">
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

// ============================================================
// Candidates table
// ============================================================

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
          const isNew = newCodes.has(s.stockCode);
          const isSelected = selectedCode === s.stockCode;
          return (
            <tr
              key={s.stockCode}
              className={`border-t border-zinc-800 hover:bg-zinc-800/40 cursor-pointer ${
                isNew ? "leading-stock-new" : ""
              } ${
                isSelected
                  ? "bg-emerald-900 border-l-2 border-l-emerald-700"
                  : ""
              }`}
              onClick={() => onOpen(s.stockCode)}
            >
              <td className="px-4 py-3 text-zinc-400">{s.rank}</td>
              <td className="px-4 py-3">
                <div className="font-medium">{s.stockName}</div>
                <div className="text-xs text-zinc-500 num">{code}</div>
              </td>
              <td className="px-4 py-3 text-right num">
                {/* 가격 변동 flash — 상승 빨강, 하락 파랑 (한국 거래소 관행) */}
                <FlashOnChange value={s.currentPrice} duration={1000}>
                  {formatPrice(s.currentPrice)}
                </FlashOnChange>
              </td>
              <td className="px-4 py-3 text-right num">
                {/* 키움은 등락률을 이미 % 단위로 주고, formatPct는 분수→% 변환이라 /100 해서 맞춤 */}
                <FlashOnChange value={s.priceChangeRate} duration={1000}>
                  <ProfitText
                    value={s.priceChangeRate / 100}
                    format={formatPct}
                  />
                </FlashOnChange>
              </td>
              <td className="px-4 py-3 text-right num text-zinc-300">
                {formatKoreanMoney(s.accumulatedTradingValue)}
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
// Detail panel (우측 인라인)
// ============================================================

function DetailPanel({
  stockCode,
  onClose,
}: {
  stockCode: string;
  onClose: () => void;
}) {
  const detailQ = useLeadingStockDetail(stockCode);
  const detail = detailQ.data;

  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden flex flex-col max-h-[calc(100vh-8rem)]">
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
                value={detail.priceChangeRate / 100}
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
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
            <FilterResultsList results={detail.filterResults} />
            <InvestorTrendSection stockCode={shortCode(stockCode)} />
          </div>
        ) : null}
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

// ============================================================
// 외국인·기관 자금 흐름
// ============================================================

function InvestorTrendSection({ stockCode }: { stockCode: string }) {
  const { data, isLoading, isError } = useInvestorTrend(stockCode);

  if (isLoading) {
    return (
      <section>
        <h3 className="text-sm font-semibold text-zinc-200 mb-2">
          외국인·기관 자금 흐름
        </h3>
        <Skeleton className="h-24 w-full" />
      </section>
    );
  }
  if (isError || !data || data.length === 0) return null;

  const today = data[0];
  const last5 = data.slice(0, 5);
  const sum = (sel: (d: InvestorTrendDay) => number) =>
    last5.reduce((acc, d) => acc + sel(d), 0);

  return (
    <section>
      <h3 className="text-sm font-semibold text-zinc-200 mb-3">
        외국인·기관 자금 흐름
        <span className="ml-2 text-xs font-normal text-zinc-500">
          5분 단위 갱신
        </span>
      </h3>
      <div className="bg-zinc-950 border border-zinc-800 rounded-lg p-4 space-y-4">
        <FlowGroup
          label={`오늘 ${today.date.slice(5)}`}
          rows={[
            { name: "외국인", total: today.foreignNet, nxt: today.foreignNetNxt },
            { name: "기관", total: today.institutionNet, nxt: today.institutionNetNxt },
            { name: "개인", total: today.individualNet, nxt: today.individualNetNxt },
          ]}
        />
        <div className="border-t border-zinc-800" />
        <FlowGroup
          label={`최근 ${last5.length}일 누적`}
          rows={[
            { name: "외국인", total: sum((d) => d.foreignNet), nxt: sum((d) => d.foreignNetNxt) },
            { name: "기관", total: sum((d) => d.institutionNet), nxt: sum((d) => d.institutionNetNxt) },
            { name: "개인", total: sum((d) => d.individualNet), nxt: sum((d) => d.individualNetNxt) },
          ]}
        />
      </div>
    </section>
  );
}

function FlowGroup({
  label,
  rows,
}: {
  label: string;
  rows: Array<{ name: string; total: number; nxt: number }>;
}) {
  return (
    <div>
      <div className="text-xs text-zinc-500 mb-2">{label}</div>
      {/* 3열 표: 라벨 / 전체(SOR통합) / NXT 단독 */}
      <div className="grid grid-cols-[auto_1fr_1fr] gap-x-6 gap-y-1.5 text-sm">
        <span></span>
        <span className="text-xs text-zinc-500 text-right">전체</span>
        <span className="text-xs text-zinc-500 text-right">NXT</span>
        {rows.map((r) => (
          <FlowRow key={r.name} {...r} />
        ))}
      </div>
    </div>
  );
}

function FlowRow({
  name,
  total,
  nxt,
}: {
  name: string;
  total: number;
  nxt: number;
}) {
  return (
    <>
      <span className="text-zinc-400">{name}</span>
      <SignedAmount millionWon={total} />
      <SignedAmount millionWon={nxt} />
    </>
  );
}

function SignedAmount({ millionWon }: { millionWon: number }) {
  // 한국 거래소 관행 — 양수(매수) 빨강 / 음수(매도) 파랑
  const tone =
    millionWon > 0
      ? "text-red-600"
      : millionWon < 0
        ? "text-blue-600"
        : "text-zinc-500";
  const sign = millionWon > 0 ? "+" : "";
  return (
    <span className={`${tone} num font-medium text-right`}>
      {sign}
      {formatKoreanMoney(millionWon * 1_000_000)}
    </span>
  );
}
