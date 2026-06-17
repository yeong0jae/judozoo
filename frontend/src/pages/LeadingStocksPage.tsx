import { Fragment, useEffect, useRef, useState } from "react";
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
  SwingHighSignal,
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
  // 당일 등락률 임계값(%) — 사용자가 1~7 중 선택. 기본 7%.
  const [minChangeRate, setMinChangeRate] = useState(7);
  const candidatesQ = useLeadingStockCandidates(minChangeRate);
  const [openCode, setOpenCode] = useState<string | null>(null);

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
          {/* 등락률 임계값 선택 — 리스트 우측 상단 */}
          <div className="flex justify-end px-4 py-2 border-b border-zinc-800">
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
            <DetailPanel stockCode={openCode} />
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

// 당일 등락률 임계값 선택지 — -7 ~ +7 중 홀수 구간 + 0
const CHANGE_RATE_OPTIONS = [-7, -5, -3, 0, 3, 5, 7];

/** 당일 등락률 임계값 선택 — -7~7% 세그먼트 버튼 */
function ChangeRateSelector({
  value,
  onChange,
}: {
  value: number;
  onChange: (rate: number) => void;
}) {
  return (
    <div className="flex items-center gap-1.5 flex-wrap justify-end">
      <span className="text-xs text-zinc-500">등락률</span>
      <div className="inline-flex flex-wrap rounded-md overflow-hidden border border-zinc-700">
        {CHANGE_RATE_OPTIONS.map((rate) => (
          <button
            key={rate}
            type="button"
            onClick={() => onChange(rate)}
            className={`px-2 py-0.5 text-xs font-medium border-l border-zinc-700 first:border-l-0 transition-colors ${
              rate === value
                ? "bg-emerald-600 text-white"
                : "bg-zinc-900 text-zinc-400 hover:bg-zinc-800"
            }`}
            aria-pressed={rate === value}
          >
            {rate > 0 ? `+${rate}` : rate}
          </button>
        ))}
        <span className="px-1.5 py-0.5 text-xs text-zinc-500 bg-zinc-900 border-l border-zinc-700">
          %
        </span>
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
    <table className="hidden md:table w-full text-sm">
      <thead className="bg-zinc-950 text-zinc-500 text-xs">
        <tr>
          <th className="px-4 py-2.5 text-left whitespace-nowrap">순위</th>
          <th className="px-4 py-2.5 text-left">종목</th>
          <th className="px-4 py-2.5 text-right">현재가</th>
          <th className="px-4 py-2.5 text-right">등락률</th>
          <th className="px-4 py-2.5 text-right">거래대금</th>
          <th className="px-4 py-2.5 w-24"></th>
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
            </Fragment>
          );
        })}
      </tbody>
    </table>
  );
}

function GroupHeader({ label, hint }: { label: string; hint?: string }) {
  return (
    <tr aria-hidden className="bg-zinc-950 border-t border-zinc-800">
      {/* 표 좌측 끝(순위 컬럼 자리)에서 라벨 시작 — 1·2·3 번호 컬럼과 좌측 정렬 일치 */}
      <td colSpan={6} className="px-4 py-3">
        <span className="text-sm font-semibold text-zinc-200">{label}</span>
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
  const navigate = useNavigate();
  return (
    <div className="md:hidden">
      {stocks.map((s, idx) => {
        const code = shortCode(s.stockCode);
        const isNew = newCodes.has(s.stockCode);
        const isSelected = selectedCode === s.stockCode;
        return (
          <Fragment key={s.stockCode}>
            {idx === 0 && <CardGroupHeader label="거래대금 1, 2, 3위" />}
            {idx === 3 && <CardGroupHeader label="주도주 후보" />}
            <div
              className={`border-t border-zinc-800 px-4 py-3 flex flex-col gap-1 cursor-pointer ${
                isNew ? "leading-stock-new" : ""
              } ${isSelected ? "bg-emerald-900 border-l-2 border-l-emerald-700" : ""}`}
              onClick={() => onOpen(s.stockCode)}
            >
              {/* 1행: 순위 · 종목명 · 현재가 */}
              <div className="flex items-center gap-2">
                <span className="text-zinc-400 text-sm w-5 shrink-0">{s.rank}</span>
                <span className="font-medium truncate flex-1 min-w-0">
                  {s.stockName}
                </span>
                <FlashOnChange value={s.currentPrice} duration={1000}>
                  <span className="num shrink-0">{formatPrice(s.currentPrice)}</span>
                </FlashOnChange>
              </div>
              {/* 2행: 코드·거래대금 · 등락률 · 매매 */}
              <div className="flex items-center gap-2 pl-7">
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
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    navigate(
                      `/command?stockCode=${code}&stockName=${encodeURIComponent(s.stockName)}`,
                    );
                  }}
                  className="shrink-0 px-2.5 py-1 rounded-md text-xs font-medium bg-emerald-700 hover:bg-emerald-600 text-white"
                >
                  매매
                </button>
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

// ============================================================
// Detail panel (우측 인라인)
// ============================================================

function DetailPanel({ stockCode }: { stockCode: string }) {
  const detailQ = useLeadingStockDetail(stockCode);
  const detail = detailQ.data;

  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden flex flex-col lg:max-h-[calc(100vh-8rem)]">
      <header className="px-4 sm:px-6 py-4 border-b border-zinc-800">
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
            {detail.relativeVolume != null && (
              <span
                className={`num ml-2 text-xs ${
                  detail.relativeVolume >= 2
                    ? "text-amber-400"
                    : detail.relativeVolume >= 1
                      ? "text-zinc-300"
                      : "text-zinc-600"
                }`}
                title="당일 누적 거래량 / 직전 20거래일 평균 (장 초반엔 낮게 나옴)"
              >
                RVOL {detail.relativeVolume.toFixed(1)}배
              </span>
            )}
          </div>
        )}
      </header>

      <div className="flex-1 lg:overflow-y-auto p-4 sm:p-6">
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
            <div className="space-y-6">
              <InvestorTrendSection stockCode={shortCode(stockCode)} />
              <BreakoutSignalSection signal={detail.swingHighSignal} />
            </div>
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

function BreakoutSignalSection({ signal }: { signal: SwingHighSignal | null }) {
  if (!signal) return null;

  const broke = signal.gapRate <= 0;
  // 키움 분봉 cntr_tm이 HTS 표시보다 1분 이르게 라벨링됨 — HTS 기준으로 +1분 보정
  const peakDate = new Date(signal.peakAt);
  peakDate.setMinutes(peakDate.getMinutes() + 1);
  const peakTime = peakDate.toTimeString().slice(0, 5); // HH:mm

  return (
    <section>
      <h3 className="text-sm font-semibold text-zinc-200 mb-3">
        주도주 돌파 매매 시그널
        <span className="ml-2 text-xs font-normal text-zinc-500">
          좌우 5분 봉우리(피벗) 기준
        </span>
      </h3>
      <div className="bg-zinc-950 border border-zinc-800 rounded-lg p-4">
        <div className="flex items-baseline justify-between">
          <span className="text-sm text-zinc-400">
            {broke ? "전고점 돌파" : "전고점까지"}
          </span>
          <span
            className={`num text-lg font-semibold ${
              broke
                ? "text-emerald-400"
                : signal.gapRate < 1
                  ? "text-amber-400"
                  : "text-zinc-200"
            }`}
          >
            {broke ? `+${(-signal.gapRate).toFixed(1)}%` : `${signal.gapRate.toFixed(1)}%`}
          </span>
        </div>
        <div className="mt-2 flex items-baseline justify-between text-xs text-zinc-500">
          <span>전고점 {formatPrice(signal.peakPrice)}</span>
          <span className="num">{peakTime} 형성</span>
        </div>
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
