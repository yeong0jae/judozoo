import { Fragment, useEffect, useRef, useState } from "react";
import {
  useDailyCandles,
  useInvestorTrend,
  useLeadingStockCandidates,
  useLeadingStockDetail,
  useMinuteCandles,
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
import NumWon from "../components/common/NumWon";
import StockAvatar from "../components/common/StockAvatar";
import CandleChart, {
  dailySeries,
  minuteSeries,
} from "../components/common/CandleChart";
import ChangeRateSelector, {
  CHANGE_RATE_OPTIONS,
} from "../components/common/ChangeRateSelector";

/**
 * 키움 마스터 코드 — 거래 ID로는 6자리 단축코드만 사용.
 * "009150_AL" 같이 거래소 접미사가 붙어 오면 "_" 앞쪽으로 잘라낸다.
 */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

export default function LeadingStocksPage() {
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
            ? "grid grid-cols-1 lg:grid-cols-[9fr_11fr] gap-6 items-start"
            : ""
        }
      >
        <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
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

const MIN_CHANGE_RATE_KEY = "leadingStock.minChangeRate";

// ============================================================
// Candidates table
// ============================================================

/** 종목의 대표 테마 칩. 전체 테마가 더 많으면 "+N" 표기. */
function ThemeChips({ themes, themeCount }: { themes: string[]; themeCount: number }) {
  if (themes.length === 0) return null;
  const extra = themeCount - themes.length;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {themes.map((t) => (
        <span
          key={t}
          className="text-[11px] px-2 py-0.5 rounded-full bg-white/[0.06] text-zinc-400"
        >
          {t}
        </span>
      ))}
      {extra > 0 && <span className="text-[11px] text-zinc-500">+{extra}</span>}
    </div>
  );
}

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
    <table className="hidden md:table w-full text-xs">
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
                className={`border-t border-white/[0.04] hover:bg-white/[0.03] cursor-pointer transition-colors ${
                  isNew ? "leading-stock-new" : ""
                } ${isSelected ? "bg-emerald-900" : ""}`}
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
    <tr aria-hidden className="border-t border-white/[0.04] bg-white/[0.02]">
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
              className={`border-t border-white/[0.04] px-4 py-3.5 flex flex-col gap-1 cursor-pointer ${
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

// ============================================================
// Detail panel (우측 인라인)
// ============================================================

type DetailTab = "detail" | "minute" | "daily";

function DetailPanel({ stockCode }: { stockCode: string }) {
  const detailQ = useLeadingStockDetail(stockCode);
  const detail = detailQ.data;
  const [tab, setTab] = useState<DetailTab>("detail");
  const minuteQ = useMinuteCandles(tab === "minute" ? stockCode : null);
  const dailyQ = useDailyCandles(tab === "daily" ? stockCode : null);
  const CH = "h-[28rem]";

  const TABS: { key: DetailTab; label: string }[] = [
    { key: "detail", label: "상세" },
    { key: "minute", label: "1분봉" },
    { key: "daily", label: "일봉" },
  ];

  return (
    <div className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden flex flex-col lg:max-h-[calc(100vh-8rem)]">
      <header className="px-4 sm:px-6 py-4 border-b border-white/[0.04]">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex items-center gap-3">
              <StockAvatar name={detail?.stockName ?? "?"} code={shortCode(stockCode)} size={36} />
              <div className="flex items-center flex-wrap gap-x-2 gap-y-1">
                <span className="text-base font-semibold">{detail?.stockName ?? "…"}</span>
                <span className="text-xs text-zinc-500 num">{shortCode(stockCode)}</span>
                {detail && (
                  <ThemeChips themes={detail.themes} themeCount={detail.themes.length} />
                )}
              </div>
            </div>
            {detail && (
              <div className="text-xs text-zinc-400 mt-0.5">
                <NumWon value={detail.currentPrice} className="num" />{" "}
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
          </div>
          {/* 상세/차트 토글 */}
          <div className="flex rounded-lg bg-white/[0.04] p-0.5 text-xs shrink-0">
            {TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => setTab(t.key)}
                className={`px-2.5 py-1 rounded-md transition-colors ${
                  tab === t.key ? "bg-white/[0.1] text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
                }`}
              >
                {t.label}
              </button>
            ))}
          </div>
        </div>
      </header>

      <div className="flex-1 lg:overflow-y-auto p-4 sm:p-6">
        {tab === "detail" ? (
          detailQ.isLoading ? (
            <div className="space-y-2">
              {Array.from({ length: 6 }).map((_, i) => (
                <Skeleton key={i} className="h-12 w-full" />
              ))}
            </div>
          ) : detailQ.isError ? (
            <p className="text-xs text-rose-700">상세 정보를 불러올 수 없습니다</p>
          ) : detail ? (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
              <FilterResultsList results={detail.filterResults} />
              <div className="space-y-6">
                <BreakoutSignalSection
                  signal={detail.swingHighSignal}
                  currentPrice={detail.currentPrice}
                />
                <InvestorTrendSection stockCode={shortCode(stockCode)} />
              </div>
            </div>
          ) : null
        ) : tab === "minute" ? (
          minuteQ.isLoading ? (
            <Skeleton className={`${CH} w-full`} />
          ) : !minuteQ.data || minuteQ.data.length === 0 ? (
            <div className={`${CH} flex items-center justify-center text-xs text-zinc-600`}>
              분봉 데이터가 없습니다
            </div>
          ) : (
            <CandleChart
              key={`${stockCode}-m`}
              series={minuteSeries(minuteQ.data)}
              priceLine={Math.max(...minuteQ.data.map((c) => c.high))}
              className={`w-full ${CH}`}
            />
          )
        ) : dailyQ.isLoading ? (
          <Skeleton className={`${CH} w-full`} />
        ) : !dailyQ.data || dailyQ.data.length === 0 ? (
          <div className={`${CH} flex items-center justify-center text-xs text-zinc-600`}>
            일봉 데이터가 없습니다
          </div>
        ) : (
          <CandleChart
            key={`${stockCode}-d`}
            series={dailySeries(dailyQ.data)}
            timeVisible={false}
            className={`w-full ${CH}`}
          />
        )}
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
          <span className="text-xs mt-0.5">{r.passed ? "✓" : "✗"}</span>
          <div className="flex-1 min-w-0">
            <div className="text-xs font-medium">{r.filterName}</div>
            <div className="text-xs text-zinc-500 mt-0.5">
              기준: {r.criteriaDescription}
            </div>
          </div>
          <div className="text-xs num shrink-0">{r.actualValue}</div>
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
        <h3 className="text-xs font-semibold text-zinc-200 mb-2">
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
      <h3 className="text-xs font-semibold text-zinc-200 mb-3">
        외국인·기관 자금 흐름
        <span className="ml-2 text-xs font-normal text-zinc-500">
          5분 단위 갱신
        </span>
      </h3>
      <div className="bg-zinc-950 border border-white/[0.04] rounded-xl p-4 space-y-4">
        <FlowGroup
          label={`오늘 ${today.date.slice(5)}`}
          rows={[
            { name: "개인", total: today.individualNet, nxt: today.individualNetNxt },
            { name: "외국인", total: today.foreignNet, nxt: today.foreignNetNxt },
            { name: "기관", total: today.institutionNet, nxt: today.institutionNetNxt },
          ]}
        />
        <div className="border-t border-zinc-800" />
        <FlowGroup
          label={`최근 ${last5.length}일 누적`}
          rows={[
            { name: "개인", total: sum((d) => d.individualNet), nxt: sum((d) => d.individualNetNxt) },
            { name: "외국인", total: sum((d) => d.foreignNet), nxt: sum((d) => d.foreignNetNxt) },
            { name: "기관", total: sum((d) => d.institutionNet), nxt: sum((d) => d.institutionNetNxt) },
          ]}
        />
      </div>
    </section>
  );
}

// 전고점 형성 후 경과 시간을 사람이 읽기 좋게 — 신선한 고점일수록 돌파 매매에 유효
function formatElapsed(ms: number): string {
  const min = Math.floor(ms / 60000);
  if (min <= 0) return "방금";
  if (min < 60) return `${min}분 전`;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return m === 0 ? `${h}시간 전` : `${h}시간 ${m}분 전`;
}

// 잔여 상승률(%)로 돌파 임박도를 라벨/색으로 구분
function breakoutStatus(gapRate: number): {
  label: string;
  chip: string;
  gap: string;
} {
  if (gapRate <= 0)
    return { label: "돌파", chip: "bg-emerald-500/15 text-emerald-400", gap: "text-emerald-400" };
  if (gapRate < 2.0)
    return { label: "임박", chip: "bg-amber-500/20 text-amber-300", gap: "text-amber-300" };
  if (gapRate < 4.0)
    return { label: "주시", chip: "bg-amber-500/15 text-amber-400", gap: "text-amber-400" };
  return { label: "관망", chip: "bg-zinc-700/40 text-zinc-400", gap: "text-zinc-200" };
}

function BreakoutSignalSection({
  signal,
  currentPrice,
}: {
  signal: SwingHighSignal | null;
  currentPrice: number;
}) {
  if (!signal) return null;

  const broke = signal.gapRate <= 0;
  // 키움 분봉 cntr_tm이 HTS 표시보다 1분 이르게 라벨링됨 — HTS 기준으로 +1분 보정
  const peakDate = new Date(signal.peakAt);
  peakDate.setMinutes(peakDate.getMinutes() + 1);
  const peakTime = peakDate.toTimeString().slice(0, 5); // HH:mm
  const elapsed = formatElapsed(Date.now() - peakDate.getTime());

  const gapWon = signal.peakPrice - currentPrice; // 돌파까지 더 올라야 하는 금액 (돌파 시 음수)
  const status = breakoutStatus(signal.gapRate);

  return (
    <section>
      <h3 className="text-xs font-semibold text-zinc-200 mb-3">
        주도주 돌파 매매 시그널
        <span className="ml-2 text-xs font-normal text-zinc-500">
          최근 3거래일 고가 기준
        </span>
      </h3>
      <div className="bg-zinc-950 border border-white/[0.04] rounded-xl p-4">
        {/* 돌파선(전고점) + 임박도 칩 */}
        <div className="flex items-baseline justify-between">
          <span className="text-xs text-zinc-400">돌파선</span>
          <span className="flex items-baseline gap-2">
            <span className="num text-sm font-semibold text-zinc-100">
              {formatPrice(signal.peakPrice)}
            </span>
            <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${status.chip}`}>
              {status.label}
            </span>
          </span>
        </div>
        <div className="mt-1 text-xs text-zinc-500 num">
          {peakDate.getDate()}일 {peakTime} 형성 · {elapsed}
        </div>

        <div className="my-3 border-t border-zinc-800" />

        {/* 현재가 → 돌파까지 거리(원/%) */}
        <div className="flex items-baseline justify-between">
          <span className="text-xs text-zinc-400">현재가</span>
          <span className="num text-sm text-zinc-300">{formatPrice(currentPrice)}</span>
        </div>
        <div className="mt-2 flex items-baseline justify-between">
          <span className="text-xs text-zinc-400">{broke ? "돌파" : "돌파까지"}</span>
          <span className={`num text-sm font-semibold ${status.gap}`}>
            {broke
              ? `${formatPrice(-gapWon)}원 (+${(-signal.gapRate).toFixed(1)}%)`
              : `${formatPrice(gapWon)}원 (${signal.gapRate.toFixed(1)}%)`}
          </span>
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
      <div className="grid grid-cols-[auto_1fr_1fr] gap-x-6 gap-y-1.5 text-xs">
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
