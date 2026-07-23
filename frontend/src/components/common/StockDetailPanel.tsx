import { useState } from "react";
import {
  useDailyCandles,
  useLeadingStockDetail,
  useMinuteCandles,
  useStockInvestorDaily,
} from "../../api/queries";
import type {
  FilterResultItem,
  StockOrgBreakdown,
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
      <div className="h-[28rem] flex items-center justify-center text-sm text-zinc-600">
        종목을 선택하면 표시됩니다
      </div>
    );
  }

  return (
    <div className="flex flex-col lg:max-h-[calc(100vh-8rem)]">
      <header className="pb-4 border-b border-zinc-800">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex items-center gap-3">
              <StockAvatar name={detail?.stockName ?? "?"} code={shortCode(stockCode)} size={40} />
              <div className="flex items-center flex-wrap gap-x-2 gap-y-1">
                <span className="text-lg font-bold tracking-tight text-zinc-100">{detail?.stockName ?? "…"}</span>
                <span className="text-xs text-zinc-500 num">{shortCode(stockCode)}</span>
                {detail && (
                  <ThemeChips themes={detail.themes} themeCount={detail.themes.length} />
                )}
              </div>
            </div>
            {detail && (
              <div className="mt-1 flex items-baseline gap-2 flex-wrap">
                <NumWon value={detail.currentPrice} className="num text-xl font-bold text-zinc-100" />
                <ProfitText
                  value={detail.priceChangeRate / 100}
                  format={formatPct}
                  className="num text-sm font-semibold"
                />
                {detail.relativeVolume != null && (
                  <span
                    className={`num text-xs ${
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
          <div className="flex rounded-xl bg-white/[0.04] p-0.5 text-xs shrink-0">
            {TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => setTab(t.key)}
                className={`px-3 py-1.5 rounded-lg transition-colors ${
                  tab === t.key ? "bg-white/[0.1] text-zinc-100 font-medium" : "text-zinc-500 hover:text-zinc-300"
                }`}
              >
                {t.label}
              </button>
            ))}
          </div>
        </div>
      </header>

      <div className="flex-1 lg:overflow-y-auto pt-5">
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
    <div className="space-y-1">
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm font-semibold text-zinc-400">필터</span>
        <span className="text-xs num text-zinc-400">
          <span className="text-emerald-400 font-semibold">{passedCount}</span> / {results.length} 통과
        </span>
      </div>
      {results.map((r) => (
        <div
          key={r.filterName}
          className="flex items-center gap-3 px-3 py-2.5 rounded-xl bg-zinc-900"
        >
          <div className="flex-1 min-w-0">
            <div className="text-xs font-medium text-zinc-200">{r.filterName}</div>
            <div className="text-xs text-zinc-500 mt-0.5">{r.criteriaDescription}</div>
          </div>
          <div className="num text-xs text-zinc-300 shrink-0">{r.actualValue}</div>
          <span
            className={`text-[11px] px-2 py-0.5 rounded-md shrink-0 ${
              r.passed ? "bg-emerald-400/10 text-emerald-400" : "bg-rose-500/10 text-rose-400"
            }`}
          >
            {r.passed ? "통과" : "미달"}
          </span>
        </div>
      ))}
    </div>
  );
}

// ============================================================
// 외국인·기관 자금 흐름
// ============================================================

// 기관 세부 순서 — 시황분석 종목 상세와 동일. 기관계 아래 들여쓰기로 표시.
const ORG_DETAIL: { key: keyof StockOrgBreakdown; label: string }[] = [
  { key: "financialInvestmentMillion", label: "금융투자" },
  { key: "insuranceMillion", label: "보험" },
  { key: "otherFinanceMillion", label: "기타금융" },
  { key: "trustMillion", label: "투신" },
  { key: "privateEquityMillion", label: "사모펀드" },
  { key: "pensionFundMillion", label: "연기금등" },
  { key: "bankMillion", label: "은행" },
];

/** 당일 투자자 수급 — 개인·외국인·기관계(세부)·기타법인. 키움 ka10059(전체·SOR통합). */
function InvestorTrendSection({ stockCode }: { stockCode: string }) {
  const { data, isLoading, isError } = useStockInvestorDaily(stockCode, 1);

  if (isLoading) {
    return (
      <section>
        <span className="text-sm font-semibold text-zinc-400 block mb-2">외인·기관 자금 흐름</span>
        <Skeleton className="h-24 w-full" />
      </section>
    );
  }
  if (isError || !data || data.length === 0) return null;

  const today = data[0];

  return (
    <section>
      <div className="flex items-baseline justify-between mb-3">
        <span className="text-sm font-semibold text-zinc-400">외인·기관 자금 흐름</span>
        <span className="text-xs text-zinc-600">오늘 {today.date.slice(5)}</span>
      </div>
      <div className="bg-zinc-900 rounded-2xl px-4 py-2">
        <table className="w-full text-xs">
          <thead className="text-zinc-600">
            <tr>
              <th className="text-left font-normal py-1.5">구분</th>
              <th className="text-right font-normal py-1.5">순매수</th>
            </tr>
          </thead>
          <tbody>
            <FlowRow name="개인" million={today.individualMillion} />
            <FlowRow name="외국인" million={today.foreignMillion} />
            <FlowRow name="기관계" million={today.institutionMillion} emphasis />
            {ORG_DETAIL.map((o) => (
              <FlowRow key={o.key} name={o.label} million={today.breakdown[o.key]} indent />
            ))}
            <FlowRow name="기타법인" million={today.otherCorpMillion} />
          </tbody>
        </table>
      </div>
    </section>
  );
}

/**
 * 수급 표 한 행 — 구분 / 순매수. 기관계는 강조, 기관 세부는 들여쓰기.
 * 입력은 백만원이지만 이 패널은 억/조로 반올림해 보여준다(시황분석 종목 표만 백만원 정밀).
 */
function FlowRow({
  name,
  million,
  indent,
  emphasis,
}: {
  name: string;
  million: number;
  indent?: boolean;
  emphasis?: boolean;
}) {
  const tone = million > 0 ? "text-red-400" : million < 0 ? "text-blue-400" : "text-zinc-500";
  const sign = million > 0 ? "+" : "";
  const weight = emphasis ? "font-semibold" : "font-medium";
  return (
    <tr className="border-t border-zinc-800/60">
      <td className={`text-left py-1.5 ${indent ? "pl-3 text-zinc-500" : "text-zinc-400"} ${emphasis ? "font-semibold text-zinc-300" : ""}`}>
        {name}
      </td>
      <td className={`text-right py-1.5 num ${tone} ${weight}`}>
        {sign}
        {formatKoreanMoney(Math.round(million / 100) * 100_000_000)}
      </td>
    </tr>
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
      <div className="flex items-baseline justify-between mb-3">
        <span className="text-sm font-semibold text-zinc-400">주도주 돌파 매매 시그널</span>
        <span className="text-xs text-zinc-600">최근 3거래일 고가</span>
      </div>
      <div className="bg-zinc-900 rounded-2xl p-4">
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

        <div className="my-3 h-px bg-zinc-800" />

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

