import { useState } from "react";
import {
  useDailyCandles,
  useInvestorTrend,
  useLeadingStockDetail,
  useMinuteCandles,
} from "../../api/queries";
import type {
  FilterResultItem,
  InvestorTrendDay,
  SwingHighSignal,
} from "../../types";
import { formatKoreanMoney, formatPct, formatPrice } from "../../lib/format";
import CandleChart, { dailySeries, minuteSeries } from "./CandleChart";
import { todayStr } from "./DateNavigator";
import NumWon from "./NumWon";
import ProfitText from "./ProfitText";
import Skeleton from "./Skeleton";
import StockAvatar from "./StockAvatar";
import ThemeChips from "./ThemeChips";

/** 키움 마스터 코드 — "009150_AL" 같이 거래소 접미사가 붙으면 앞쪽 6자리만. */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

type DetailTab = "detail" | "minute" | "daily";

/**
 * 종목 상세/차트 통합 패널 — [상세 | 1분봉 | 일봉] 토글.
 * 후보·돌파·스파이크·시그널 로그 공용. defaultTab으로 페이지 성격에 맞춰 기본 탭 지정.
 */
export default function StockDetailPanel({
  stockCode,
  defaultTab = "detail",
  date = todayStr(),
}: {
  stockCode: string | null;
  defaultTab?: DetailTab;
  date?: string; // 차트 기준 날짜 — 미지정 시 오늘
}) {
  const detailQ = useLeadingStockDetail(stockCode);
  const detail = detailQ.data;
  const [tab, setTab] = useState<DetailTab>(defaultTab);
  const minuteQ = useMinuteCandles(tab === "minute" ? stockCode : null, date);
  const dailyQ = useDailyCandles(tab === "daily" ? stockCode : null, date);
  const CH = "h-[28rem]";

  const TABS: { key: DetailTab; label: string }[] = [
    { key: "detail", label: "상세" },
    { key: "minute", label: "1분봉" },
    { key: "daily", label: "일봉" },
  ];

  if (!stockCode) {
    return (
      <div className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
        <div className="h-[28rem] flex items-center justify-center text-sm text-zinc-600">
          종목을 선택하면 표시됩니다
        </div>
      </div>
    );
  }

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
            r.passed ? "bg-emerald-50 border-emerald-200" : "bg-rose-50 border-rose-200"
          }`}
        >
          <span className="text-xs mt-0.5">{r.passed ? "✓" : "✗"}</span>
          <div className="flex-1 min-w-0">
            <div className="text-xs font-medium">{r.filterName}</div>
            <div className="text-xs text-zinc-500 mt-0.5">기준: {r.criteriaDescription}</div>
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
        <h3 className="text-xs font-semibold text-zinc-200 mb-2">외국인·기관 자금 흐름</h3>
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
        <span className="ml-2 text-xs font-normal text-zinc-500">5분 단위 갱신</span>
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
function breakoutStatus(gapRate: number): { label: string; chip: string; gap: string } {
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
        <span className="ml-2 text-xs font-normal text-zinc-500">최근 3거래일 고가 기준</span>
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

function FlowRow({ name, total, nxt }: { name: string; total: number; nxt: number }) {
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
    millionWon > 0 ? "text-red-600" : millionWon < 0 ? "text-blue-600" : "text-zinc-500";
  const sign = millionWon > 0 ? "+" : "";
  return (
    <span className={`${tone} num font-medium text-right`}>
      {sign}
      {formatKoreanMoney(millionWon * 1_000_000)}
    </span>
  );
}
