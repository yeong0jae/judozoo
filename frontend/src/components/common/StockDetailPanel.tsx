import { useState } from "react";
import { createPortal } from "react-dom";
import {
  useDailyCandles,
  useLeadingStockDetail,
  useMinuteCandles,
  useStockInvestorDaily,
} from "../../api/queries";
import type {
  FilterResultItem,
  StockOrgBreakdown,
} from "../../types";
import { formatKoreanMoney, formatPct } from "../../lib/format";
import CandleChart, { dailySeries, minuteSeries } from "./CandleChart";
import { todayStr } from "./DateNavigator";
import NumWon from "./NumWon";
import ProfitText from "./ProfitText";
import Skeleton from "./Skeleton";
import StockAvatar from "./StockAvatar";

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
              <StockAvatar name={detail?.stockName ?? "?"} code={shortCode(stockCode)} size={30} />
              <div className="flex items-center flex-wrap gap-x-2 gap-y-1">
                <span className="text-lg font-bold tracking-tight text-zinc-100">{detail?.stockName ?? "…"}</span>
                {/* 코드와 한 덩어리로 읽히게 같은 글꼴·같은 색으로 잇는다 */}
                <span className="text-xs text-zinc-500 num">
                  {shortCode(stockCode)}
                  {detail?.market && ` · ${detail.market}`}
                </span>
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
          <div className="flex rounded-xl bg-zinc-800 p-0.5 text-xs shrink-0">
            {TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => setTab(t.key)}
                className={`px-3 py-1.5 rounded-lg transition-colors ${
                  tab === t.key ? "bg-elevated text-zinc-100 font-medium" : "text-zinc-500 hover:text-zinc-300"
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
              <InvestorTrendSection stockCode={shortCode(stockCode)} />
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
              priceLines={[
                {
                  price: Math.max(...minuteQ.data.map((c) => c.high)),
                  title: "저항선",
                  color: "#fb923c",
                },
                {
                  price: Math.min(...minuteQ.data.map((c) => c.low)),
                  title: "지지선",
                  color: "#38bdf8",
                },
              ]}
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
  const [historyOpen, setHistoryOpen] = useState(false);

  if (isLoading) {
    return (
      <section>
        <span className="text-sm font-semibold text-zinc-400 block mb-2">외인·기관 수급</span>
        <Skeleton className="h-24 w-full" />
      </section>
    );
  }
  if (isError || !data || data.length === 0) return null;

  const today = data[0];

  return (
    <section>
      <div className="flex items-baseline justify-between mb-3">
        <span className="text-sm font-semibold text-zinc-400">외인·기관 수급</span>
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
      <button
        type="button"
        onClick={() => setHistoryOpen(true)}
        className="mt-2 w-full rounded-xl border border-zinc-800 py-2 text-xs text-zinc-400 transition-colors hover:bg-zinc-850 hover:text-zinc-200"
      >
        최근 10일 수급 확인하기
      </button>
      {historyOpen && (
        <InvestorHistoryModal stockCode={stockCode} onClose={() => setHistoryOpen(false)} />
      )}
    </section>
  );
}

/** 기관상세 묶음을 가르는 세로선 — 시황분석 일별 표와 같은 규칙. */
const ORG_EDGE = "border-l border-zinc-800";
const orgPad = (i: number, len: number) =>
  `${i === 0 ? "pl-5" : "pl-2.5"} ${i === len - 1 ? "pr-5" : "pr-2.5"}`;

const fmtDay = (iso: string) => {
  const [, m, d] = iso.split("-");
  return `${Number(m)}/${Number(d)}`;
};

/** 순매수 숫자 — 부호색(+빨강/−파랑). API가 주는 백만원을 그대로 쓴다(억으로 반올림하면 기관 세부가 뭉개진다). */
function NetMillion({ million }: { million: number }) {
  const tone = million > 0 ? "text-red-400" : million < 0 ? "text-blue-400" : "text-zinc-600";
  const sign = million > 0 ? "+" : million < 0 ? "−" : "";
  return (
    <span className={`num ${tone}`}>
      {sign}
      {Math.abs(million).toLocaleString("ko-KR")}
    </span>
  );
}

/**
 * 최근 10일 수급 모달 — 시황분석의 시장 일별 표와 같은 짜임.
 *
 * 트리거가 상세 패널 안에 있어 body로 포털한다(패널 스크롤에 갇히지 않게).
 */
function InvestorHistoryModal({
  stockCode,
  onClose,
}: {
  stockCode: string;
  onClose: () => void;
}) {
  const { data, isLoading } = useStockInvestorDaily(stockCode, 10);
  const rows = data ?? [];

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4" onClick={onClose}>
      <div
        className="max-h-[85dvh] w-full max-w-5xl overflow-auto rounded-2xl border border-zinc-800 bg-zinc-900 p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-baseline justify-between gap-3">
          <h3 className="text-lg font-semibold text-zinc-100">최근 10일 수급</h3>
          <span className="text-xs text-zinc-600">순매수 · 백만원</span>
        </div>

        {isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : rows.length === 0 ? (
          <div className="py-10 text-center text-sm text-zinc-600">일별 수급 데이터가 없습니다</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full whitespace-nowrap text-xs">
              <thead className="text-zinc-500">
                <tr>
                  <th className="pb-1 pr-3" />
                  <th className="px-2.5 pb-1 text-right font-medium">개인</th>
                  <th className="px-2.5 pb-1 text-right font-medium">외국인</th>
                  <th className="pb-1 pl-2.5 pr-5 text-right font-medium">기관계</th>
                  <th
                    colSpan={ORG_DETAIL.length}
                    className={`border-b border-zinc-800 pb-1.5 text-center font-medium text-zinc-400 ${ORG_EDGE}`}
                  >
                    기관상세
                  </th>
                  <th className={`pb-1 pl-5 pr-2.5 text-right font-medium ${ORG_EDGE}`}>기타법인</th>
                </tr>
                <tr>
                  <th className="pb-1.5 pr-3 text-left font-medium">일자</th>
                  <th />
                  <th />
                  <th />
                  {ORG_DETAIL.map((c, i) => (
                    <th
                      key={c.key}
                      className={`pb-1.5 pt-1.5 text-right font-medium ${orgPad(i, ORG_DETAIL.length)} ${i === 0 ? ORG_EDGE : ""}`}
                    >
                      {c.label}
                    </th>
                  ))}
                  <th className={ORG_EDGE} />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr
                    key={r.date}
                    className="[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-zinc-850"
                  >
                    <td className="num py-2 pr-3 text-left text-zinc-400">{fmtDay(r.date)}</td>
                    <td className="px-2.5 py-2 text-right"><NetMillion million={r.individualMillion} /></td>
                    <td className="px-2.5 py-2 text-right"><NetMillion million={r.foreignMillion} /></td>
                    <td className="py-2 pl-2.5 pr-5 text-right font-medium"><NetMillion million={r.institutionMillion} /></td>
                    {ORG_DETAIL.map((c, i) => (
                      <td
                        key={c.key}
                        className={`py-2 text-right ${orgPad(i, ORG_DETAIL.length)} ${i === 0 ? ORG_EDGE : ""}`}
                      >
                        <NetMillion million={r.breakdown[c.key]} />
                      </td>
                    ))}
                    <td className={`py-2 pl-5 pr-2.5 text-right ${ORG_EDGE}`}>
                      <NetMillion million={r.otherCorpMillion} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>,
    document.body,
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
