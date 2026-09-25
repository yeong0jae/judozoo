import { useState, type ReactNode } from "react";
import {
  useDailyCandles,
  useLeadingStockDetail,
  useMinuteCandles,
  useStockInvestorDaily,
} from "../../api/queries";
import type {
  FilterResultItem,
  LeadingStockDetailResponse,
  MinuteCandleItem,
  StockInvestorDay,
  StockOrgBreakdown,
} from "../../types";
import { colorByPnL, formatKoreanMoney, formatPct, formatPrice } from "../../lib/format";
import CandleChart, { dailySeries, minuteSeries } from "./CandleChart";
import { todayStr } from "./DateNavigator";
import NumWon from "./NumWon";
import Skeleton from "./Skeleton";

/** 키움 마스터 코드 — "009150_AL" 같이 거래소 접미사가 붙으면 앞쪽 6자리만. */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

type ChartInterval = "1m" | "1d";

/** 차트 높이 — 지수·수급 상세와 같다. 차트가 autoSize라 컨테이너 높이만 바꾸면 된다. */
const CHART_H = "h-[21.25rem] 2xl:h-[26rem]";

/**
 * 종목 상세 — 머리(가격·전일 대비·시고저) / 차트 / 주도주 조건 · 투자자별 순매수.
 * 주도주·눌림·돌파·시그널 세 화면이 같이 쓴다. 스크롤은 부르는 쪽이 맡는다.
 *
 * [onBack]을 주면 모바일에서 맨 위에 "목록" 버튼을 단다 — 모바일은 목록과 상세가 한 화면씩이다.
 */
export default function StockDetailPanel({
  stockCode,
  date = todayStr(),
  onBack,
}: {
  stockCode: string | null;
  date?: string; // 차트 기준 날짜 — 미지정 시 오늘
  onBack?: () => void;
}) {
  const detailQ = useLeadingStockDetail(stockCode);
  const detail = detailQ.data;
  const [interval, setChartInterval] = useState<ChartInterval>("1m");
  // 분봉은 차트를 일봉으로 돌려도 부른다 — 머리의 기준 시각이 마지막 분봉에서 나온다
  const minuteQ = useMinuteCandles(stockCode, date);
  const dailyQ = useDailyCandles(interval === "1d" ? stockCode : null, date);

  if (!stockCode) {
    return (
      <div className="flex h-[28rem] items-center justify-center text-sm text-zinc-600">
        종목을 선택하면 표시됩니다
      </div>
    );
  }

  const minutes = minuteQ.data ?? [];

  return (
    <div className="flex flex-col gap-6">
      {onBack && (
        <button
          type="button"
          onClick={onBack}
          className="-ml-1 -mb-2 flex w-fit items-center gap-0.5 text-sm text-zinc-400 hover:text-zinc-200 lg:hidden"
        >
          <Chevron dir="left" />
          목록
        </button>
      )}

      {detailQ.isLoading ? (
        <Skeleton className="h-28 w-full" />
      ) : detailQ.isError || !detail ? (
        <p className="text-xs text-rose-700">상세 정보를 불러올 수 없습니다</p>
      ) : (
        <DetailHeader stockCode={stockCode} detail={detail} lastMinute={minutes[minutes.length - 1]} />
      )}

      <section className="rounded-2xl bg-zinc-900 p-3 sm:p-4">
        <div className="mb-2 flex items-center justify-between gap-2 px-1">
          <h3 className="text-sm font-semibold text-zinc-400">
            {interval === "1m" ? "1분봉 · 저항선·지지선" : "일봉"}
          </h3>
          <Segmented
            label="차트 주기"
            items={[
              ["1m", "1분봉"],
              ["1d", "일봉"],
            ]}
            value={interval}
            onChange={setChartInterval}
          />
        </div>
        {interval === "1m" ? (
          minuteQ.isLoading ? (
            <Skeleton className={`${CHART_H} w-full`} />
          ) : minutes.length === 0 ? (
            <ChartEmpty>분봉 데이터가 없습니다</ChartEmpty>
          ) : (
            <CandleChart
              key={`${stockCode}-m`}
              series={minuteSeries(minutes)}
              priceLines={[
                { price: Math.max(...minutes.map((c) => c.high)), title: "저항선", color: "#fb923c" },
                { price: Math.min(...minutes.map((c) => c.low)), title: "지지선", color: "#38bdf8" },
              ]}
              className={`w-full ${CHART_H}`}
            />
          )
        ) : dailyQ.isLoading ? (
          <Skeleton className={`${CHART_H} w-full`} />
        ) : !dailyQ.data || dailyQ.data.length === 0 ? (
          <ChartEmpty>일봉 데이터가 없습니다</ChartEmpty>
        ) : (
          <CandleChart
            key={`${stockCode}-d`}
            series={dailySeries(dailyQ.data)}
            timeVisible={false}
            className={`w-full ${CHART_H}`}
          />
        )}
      </section>

      {/* 넓을 때만 두 열 — 목록 옆에 붙는 상세는 폭이 좁아, 둘로 나누면 조건 칸이 한 글자씩 접힌다 */}
      <div className="grid grid-cols-1 items-start gap-7 2xl:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
        {detail && <LeadingConditions results={detail.filterResults} />}
        <InvestorSection stockCode={shortCode(stockCode)} />
      </div>
    </div>
  );
}

function ChartEmpty({ children }: { children: ReactNode }) {
  return <div className={`${CHART_H} flex items-center justify-center text-xs text-zinc-600`}>{children}</div>;
}

function Chevron({ dir }: { dir: "left" | "right" }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d={dir === "left" ? "M15 6l-6 6 6 6" : "M9 6l6 6-6 6"} />
    </svg>
  );
}

/** 탭 두 개짜리 세그먼트 — 지수·수급 화면의 토글과 같은 모양. */
function Segmented<T extends string>({
  label,
  items,
  value,
  onChange,
}: {
  label: string;
  items: [T, string][];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <div role="tablist" aria-label={label} className="flex shrink-0 rounded-xl bg-zinc-800 p-0.5 text-xs">
      {items.map(([key, text]) => (
        <button
          key={key}
          type="button"
          role="tab"
          aria-selected={value === key}
          onClick={() => onChange(key)}
          className={`rounded-lg px-3 py-1.5 transition-colors ${
            value === key ? "bg-elevated font-medium text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
          }`}
        >
          {text}
        </button>
      ))}
    </div>
  );
}

// ============================================================
// 머리
// ============================================================

const WEEKDAY = "일월화수목금토";

/** yyyy-MM-dd → "09-23(수)" */
function dayLabel(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return `${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}(${WEEKDAY[new Date(y, m - 1, d).getDay()]})`;
}

/** 마지막 분봉 → "09-23(수) 15:30 기준". 키움 분봉은 HTS보다 1분 이르게 찍혀 +1분 보정한다(차트와 같다). */
function asOfLabel(c: MinuteCandleItem): string {
  const [date, time] = c.time.split("T");
  const [h, mi] = time.split(":").map(Number);
  const t = h * 60 + mi + 1;
  return `${dayLabel(date)} ${String(Math.floor(t / 60)).padStart(2, "0")}:${String(t % 60).padStart(2, "0")} 기준`;
}

/** 부호 붙인 원 — 음수는 하이픈이 아니라 마이너스 기호. */
const signedWon = (v: number) => `${v > 0 ? "+" : v < 0 ? "−" : ""}${formatPrice(Math.abs(v))}`;

/** 이름·코드 / 큰 가격·전일 대비·등락률 / RVOL·거래대금·기준 시각. 넓으면 오른쪽에 시가·고가·저가·전일. */
function DetailHeader({
  stockCode,
  detail,
  lastMinute,
}: {
  stockCode: string;
  detail: LeadingStockDetailResponse;
  lastMinute: MinuteCandleItem | undefined;
}) {
  const pct = detail.priceChangeRate;
  // 전일 종가가 없으면(0) 전일 대비를 그리지 않는다 — 현재가 전체가 상승분처럼 읽힌다
  const hasPrev = detail.previousClose > 0;
  const chg = detail.currentPrice - detail.previousClose;
  const pctBadge = pct > 0 ? "bg-red-500/10" : pct < 0 ? "bg-blue-500/10" : "bg-zinc-800";
  const stats: [string, number][] = [
    ["시가", detail.openingPrice],
    ["고가", detail.highPrice],
    ["저가", detail.lowPrice],
    ["전일", detail.previousClose],
  ];
  // RVOL · 거래대금 · 기준 시각 — 값이 없는 칸은 빼고 가운뎃점으로 잇는다
  const facts: ReactNode[] = [
    detail.relativeVolume != null && (
      <span key="rvol" className="flex items-baseline gap-1.5" title="당일 누적 거래량 / 직전 20거래일 평균 (장 초반엔 낮게 나옴)">
        <span className="text-zinc-500">RVOL</span>
        <span className="num font-semibold text-zinc-300">{detail.relativeVolume.toFixed(1)}배</span>
      </span>
    ),
    detail.tradingValue != null && (
      <span key="value" className="flex items-baseline gap-1.5">
        <span className="text-zinc-500">거래대금</span>
        <span className="num font-semibold text-zinc-300">{formatKoreanMoney(detail.tradingValue)}</span>
      </span>
    ),
    lastMinute && (
      <span key="asof" className="num text-zinc-500">
        {asOfLabel(lastMinute)}
      </span>
    ),
  ].filter(Boolean);

  return (
    <header className="flex flex-wrap items-end justify-between gap-x-8 gap-y-3">
      <div className="flex min-w-0 flex-col gap-2">
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
          <h2 className="text-lg font-bold tracking-tight text-zinc-100">{detail.stockName}</h2>
          <span className="num text-xs text-zinc-500">
            {shortCode(stockCode)}
            {detail.market && ` · ${detail.market}`}
          </span>
        </div>
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <NumWon value={detail.currentPrice} className="num text-3xl font-bold tracking-tight text-zinc-100 sm:text-4xl" />
          {hasPrev && <span className={`num text-base font-semibold ${colorByPnL(chg)}`}>{signedWon(chg)}</span>}
          <span className={`num rounded-md px-2 py-0.5 text-[13px] font-bold ${pctBadge} ${colorByPnL(pct)}`}>
            {formatPct(pct / 100)}
          </span>
        </div>
        {facts.length > 0 && (
          <p className="flex flex-wrap items-baseline gap-x-2 gap-y-1 text-[13px]">
            {facts.flatMap((f, i) => (i === 0 ? [f] : [<span key={`dot${i}`} aria-hidden className="text-zinc-700">·</span>, f]))}
          </p>
        )}
      </div>
      {/* 모바일은 싣지 않는다 — 폭이 좁아 가격 줄 아래로 한 줄을 더 차지하고, 차트가 같은 걸 보여준다 */}
      {hasPrev && (
        <dl className="hidden gap-5 text-xs md:flex">
          {stats.map(([label, v]) => (
            <div key={label} className="flex flex-col items-end gap-1">
              <dt className="text-zinc-500">{label}</dt>
              <dd className="num font-semibold text-zinc-300">{formatPrice(v)}</dd>
            </div>
          ))}
        </dl>
      )}
    </header>
  );
}

// ============================================================
// 주도주 조건
// ============================================================

function LeadingConditions({ results }: { results: FilterResultItem[] }) {
  const passedCount = results.filter((r) => r.passed).length;
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between">
        <h3 className="text-[15px] font-bold text-zinc-100">주도주 조건</h3>
        <span className="num text-xs text-zinc-400">
          <span className="font-bold text-emerald-400">{passedCount}</span> / {results.length} 통과
        </span>
      </div>
      {/* 한 칸이 조건 하나 — 어디서 떨어졌는지가 순서로 읽힌다(판별력이 큰 조건이 앞) */}
      <div className="flex h-1.5 gap-0.5 overflow-hidden rounded-full bg-zinc-850" aria-hidden>
        {results.map((r) => (
          <span key={r.filterName} className={`flex-1 ${r.passed ? "bg-emerald-400/85" : "bg-red-400/90"}`} />
        ))}
      </div>
      <ul className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
        {results.map((r) => (
          <li key={r.filterName} className="flex items-center gap-2.5 rounded-xl bg-zinc-900 px-3 py-2.5">
            <span
              className={`inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-md ${
                r.passed ? "bg-emerald-400/10 text-emerald-400" : "bg-red-400/10 text-red-400"
              }`}
            >
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                <path d={r.passed ? "M5 12l5 5 9-10" : "M7 7l10 10M17 7L7 17"} />
              </svg>
              <span className="sr-only">{r.passed ? "통과" : "미달"}</span>
            </span>
            <span className="flex min-w-0 flex-1 flex-col gap-px">
              <span className="text-xs font-semibold text-zinc-300">{r.filterName}</span>
              <span className="truncate text-[11px] text-zinc-500">{r.criteriaDescription}</span>
            </span>
            <span className={`num shrink-0 whitespace-nowrap text-xs font-semibold ${r.passed ? "text-zinc-100" : "text-red-400"}`}>
              {r.actualValue}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

// ============================================================
// 투자자별 순매수 — 오늘 / 최근 10일
// ============================================================

// 기관 세부 순서 — 시황분석 종목 상세와 동일.
const ORG_DETAIL: { key: keyof StockOrgBreakdown; label: string }[] = [
  { key: "financialInvestmentMillion", label: "금융투자" },
  { key: "insuranceMillion", label: "보험" },
  { key: "otherFinanceMillion", label: "기타금융" },
  { key: "trustMillion", label: "투신" },
  { key: "privateEquityMillion", label: "사모펀드" },
  { key: "pensionFundMillion", label: "연기금등" },
  { key: "bankMillion", label: "은행" },
];

/** API는 백만원을 준다 — 이 패널은 억으로 반올림해 보인다(지수·수급 화면과 같은 단위). */
const toEok = (million: number) => Math.round(million / 100);

function Eok({ million, className = "" }: { million: number; className?: string }) {
  const eok = toEok(million);
  const tone = eok > 0 ? "text-red-400" : eok < 0 ? "text-blue-400" : "text-zinc-500";
  const sign = eok > 0 ? "+" : eok < 0 ? "−" : "";
  return (
    <span className={`num ${tone} ${className}`}>
      {sign}
      {Math.abs(eok).toLocaleString("ko-KR")}
    </span>
  );
}

type FlowTab = "today" | "daily";

/** 키움 ka10059(전체·SOR통합). 한 번에 10일을 받아 오늘은 첫 줄을 쓴다 — 탭을 바꿔도 다시 부르지 않는다. */
function InvestorSection({ stockCode }: { stockCode: string }) {
  const { data, isLoading, isError } = useStockInvestorDaily(stockCode, 10);
  const [tab, setTab] = useState<FlowTab>("today");
  const days = data ?? [];

  const sub = days.length === 0 ? null : tab === "today" ? `${dayLabel(days[0].date)} · 억원` : `최근 ${days.length}거래일 · 억원`;

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5">
          <h3 className="text-[15px] font-bold text-zinc-100">투자자별 순매수</h3>
          {sub && <span className="num text-xs text-zinc-500">{sub}</span>}
        </div>
        <Segmented
          label="수급 기간"
          items={[
            ["today", "오늘"],
            ["daily", "최근 10일"],
          ]}
          value={tab}
          onChange={setTab}
        />
      </div>
      {isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : isError || days.length === 0 ? (
        <p className="py-6 text-center text-xs text-zinc-600">투자자별 수급 데이터가 없습니다</p>
      ) : tab === "today" ? (
        <TodayFlow day={days[0]} />
      ) : (
        <DailyFlow days={days} />
      )}
    </section>
  );
}

/** 개인·외국인·기관 — 가운데 0에서 좌우로 뻗는 막대(셋 중 큰 값이 반 폭) + 기관 상세. */
function TodayFlow({ day }: { day: StockInvestorDay }) {
  const tiles: [string, number][] = [
    ["개인", day.individualMillion],
    ["외국인", day.foreignMillion],
    ["기관", day.institutionMillion],
  ];
  const max = Math.max(1, ...tiles.map(([, v]) => Math.abs(v)));
  return (
    <>
      <div className="grid grid-cols-3 gap-2">
        {tiles.map(([label, v]) => (
          <div key={label} className="flex flex-col gap-2 rounded-xl bg-zinc-900 px-3 py-2.5 sm:px-3.5 sm:py-3">
            <div className="flex flex-col gap-0.5 sm:flex-row sm:items-baseline sm:justify-between">
              <span className="text-xs text-zinc-400">{label}</span>
              <Eok million={v} className="text-[15px] font-bold sm:text-[17px]" />
            </div>
            <div className="relative h-1 overflow-hidden rounded-full bg-zinc-850">
              <div
                className={`absolute inset-y-0 rounded-full ${v > 0 ? "left-1/2 bg-red-400" : "right-1/2 bg-blue-400"}`}
                style={{ width: `${(Math.abs(v) / max) * 50}%` }}
              />
              <div className="absolute inset-y-[-2px] left-1/2 w-px bg-zinc-500" />
            </div>
          </div>
        ))}
      </div>
      <div className="rounded-xl bg-zinc-900 px-3.5 pb-1.5 pt-1 text-xs">
        <div className="flex justify-between py-2">
          <span className="font-semibold text-zinc-300">기관 상세</span>
          <span className="text-zinc-500">
            기타법인 <Eok million={day.otherCorpMillion} className="font-semibold" />
          </span>
        </div>
        {ORG_DETAIL.map((o) => (
          <div key={o.key} className="flex justify-between border-t border-zinc-800 py-1.5">
            <span className="text-zinc-400">{o.label}</span>
            <Eok million={day.breakdown[o.key]} className="font-semibold" />
          </div>
        ))}
      </div>
    </>
  );
}

/**
 * 최근 N일 — 맨 위에 합계, 그 아래 날짜별. 상세 칸은 좁아 기관 세부 일곱 열은 싣지 않는다
 * (오늘 탭의 기관 상세와 지수·수급 화면이 그 자리를 맡는다).
 */
function DailyFlow({ days }: { days: StockInvestorDay[] }) {
  const cols: { key: "individualMillion" | "foreignMillion" | "institutionMillion" | "otherCorpMillion"; label: string }[] = [
    { key: "individualMillion", label: "개인" },
    { key: "foreignMillion", label: "외국인" },
    { key: "institutionMillion", label: "기관" },
    { key: "otherCorpMillion", label: "기타법인" },
  ];
  const sum = (k: (typeof cols)[number]["key"]) => days.reduce((acc, d) => acc + d[k], 0);
  const td = "py-1.5 pl-2 text-right whitespace-nowrap";
  return (
    <div className="rounded-xl bg-zinc-900 py-0.5 pl-1 pr-3.5">
      <table className="w-full border-collapse text-xs">
        <thead className="text-[11px] text-zinc-500">
          <tr>
            <th className="py-1.5 pl-2.5 pr-2 text-left font-medium">일자</th>
            {cols.map((c) => (
              <th key={c.key} className={`py-1.5 pl-2 text-right ${c.key === "institutionMillion" ? "font-semibold text-zinc-300" : "font-medium"}`}>
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          <tr className="border-t border-zinc-800 bg-zinc-850/60">
            <td className="py-1.5 pl-2.5 pr-2 font-bold text-zinc-100">{days.length}일 합계</td>
            {cols.map((c) => (
              <td key={c.key} className={td}>
                <Eok million={sum(c.key)} className="font-bold" />
              </td>
            ))}
          </tr>
          {days.map((d) => (
            <tr key={d.date} className="border-t border-zinc-800">
              <td className="num py-1.5 pl-2.5 pr-2 whitespace-nowrap text-zinc-300">{dayLabel(d.date)}</td>
              {cols.map((c) => (
                <td key={c.key} className={td}>
                  <Eok million={d[c.key]} className={c.key === "institutionMillion" ? "font-bold" : "font-medium"} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
