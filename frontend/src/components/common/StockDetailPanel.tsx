import { useEffect, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import {
  useDailyCandles,
  useLeadingStockDetail,
  useMinuteCandles,
  useStockInvestorDaily,
} from "../../api/queries";
import type {
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
import { CHART_H, ChartCard, ChartEmpty, Chevron, LeadingConditions, Segmented } from "./detailParts";

/** 키움 마스터 코드 — "009150_AL" 같이 거래소 접미사가 붙으면 앞쪽 6자리만. */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

type ChartInterval = "1m" | "1d";

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
  insight,
}: {
  stockCode: string | null;
  date?: string; // 차트 기준 날짜 — 미지정 시 오늘
  onBack?: () => void;
  /** 머리 바로 아래에 끼울 것 — 주도주 화면의 "왜 오르나요?" 카드(026). 다른 화면은 비워 둔다 */
  insight?: ReactNode;
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

      {insight}

      <ChartCard
        title={interval === "1m" ? "1분봉" : "일봉"}
        action={
          <Segmented
            label="차트 주기"
            items={[
              ["1m", "1분봉"],
              ["1d", "일봉"],
            ]}
            value={interval}
            onChange={setChartInterval}
          />
        }
      >
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
      </ChartCard>

      {/* 넓을 때만 두 열 — 목록 옆에 붙는 상세는 폭이 좁아, 둘로 나누면 조건 칸이 한 글자씩 접힌다 */}
      <div className="grid grid-cols-1 items-start gap-7 2xl:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
        {detail && <LeadingConditions results={detail.filterResults} />}
        <InvestorSection stockCode={shortCode(stockCode)} />
      </div>
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
          <NumWon value={detail.currentPrice} className="num text-2xl font-bold tracking-tight text-zinc-100 sm:text-3xl" />
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

/** 키움 ka10059(전체·SOR통합). 한 번에 10일을 받아 오늘은 첫 줄을 쓴다 — 모달을 열어도 다시 부르지 않는다. */
function InvestorSection({ stockCode }: { stockCode: string }) {
  const { data, isLoading, isError } = useStockInvestorDaily(stockCode, 10);
  const [historyOpen, setHistoryOpen] = useState(false);
  const days = data ?? [];

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5">
          <h3 className="text-[15px] font-bold text-zinc-100">투자자별 순매수</h3>
          {days.length > 0 && <span className="num text-xs text-zinc-500">{dayLabel(days[0].date)} · 백만원</span>}
        </div>
        {days.length > 0 && (
          <button
            type="button"
            onClick={() => setHistoryOpen(true)}
            className="flex shrink-0 items-center gap-0.5 text-xs text-zinc-400 transition-colors hover:text-zinc-200"
          >
            최근 {days.length}일
            <Chevron dir="right" />
          </button>
        )}
      </div>
      {isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : isError || days.length === 0 ? (
        <p className="py-6 text-center text-xs text-zinc-600">투자자별 수급 데이터가 없습니다</p>
      ) : (
        <TodayFlow day={days[0]} />
      )}
      {historyOpen && <InvestorHistoryModal days={days} onClose={() => setHistoryOpen(false)} />}
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
          <div key={label} className="@container flex flex-col gap-2 rounded-xl bg-zinc-900 px-3 py-2.5 sm:px-3.5 sm:py-3">
            {/* 라벨과 값을 한 줄에 두는 건 칸이 넓을 때만 — 좁으면 "개인 -21,676"처럼 붙어 읽힌다 */}
            <div className="flex flex-col gap-0.5 @[11rem]:flex-row @[11rem]:items-baseline @[11rem]:justify-between">
              <span className="text-xs text-zinc-300">{label}</span>
              <NetMillion million={v} className="text-[15px] font-bold sm:text-[17px]" />
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
        <div className="py-2 font-semibold text-zinc-300">기관 상세</div>
        {ORG_DETAIL.map((o) => (
          <div key={o.key} className="flex justify-between border-t border-zinc-800 py-1.5">
            <span className="text-zinc-400">{o.label}</span>
            <NetMillion million={day.breakdown[o.key]} className="font-semibold" />
          </div>
        ))}
      </div>
      {/* 기타법인은 기관이 아니다 — 기관 상세 안에 두면 그 일부로 읽힌다 */}
      <div className="flex justify-between rounded-xl bg-zinc-900 px-3.5 py-2.5 text-xs">
        <span className="font-semibold text-zinc-300">기타법인</span>
        <NetMillion million={day.otherCorpMillion} className="font-semibold" />
      </div>
    </>
  );
}

/** 기관상세 묶음을 가르는 세로선 — 시황분석 일별 표와 같은 규칙. */
const ORG_EDGE = "border-l border-zinc-800";
const orgPad = (i: number, len: number) =>
  `${i === 0 ? "pl-5" : "pl-2.5"} ${i === len - 1 ? "pr-5" : "pr-2.5"}`;

/** 순매수 백만원 그대로 — 기관 세부까지 펼쳐 보여서, 억으로 반올림하면 작은 칸이 0으로 뭉개진다. 오늘과 최근 10일이 같은 단위를 쓴다. */
function NetMillion({ million, className = "" }: { million: number; className?: string }) {
  const tone = million > 0 ? "text-red-400" : million < 0 ? "text-blue-400" : "text-zinc-600";
  const sign = million > 0 ? "+" : million < 0 ? "−" : "";
  return (
    <span className={`num ${tone} ${className}`}>
      {sign}
      {Math.abs(million).toLocaleString("ko-KR")}
    </span>
  );
}

/**
 * 최근 10일 수급 모달 — 개인·외국인·기관계 + 기관 상세 일곱 열 + 기타법인, 맨 위에 합계.
 * 상세 칸은 좁아 이 열들이 다 안 들어간다. 트리거가 스크롤 칸 안에 있어 body로 포털한다.
 */
function InvestorHistoryModal({ days, onClose }: { days: StockInvestorDay[]; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const cells = (d: Pick<StockInvestorDay, "individualMillion" | "foreignMillion" | "institutionMillion" | "otherCorpMillion" | "breakdown">, strong: boolean) => (
    <>
      <td className="px-2.5 py-2 text-right"><NetMillion million={d.individualMillion} /></td>
      <td className="px-2.5 py-2 text-right"><NetMillion million={d.foreignMillion} /></td>
      <td className="py-2 pl-2.5 pr-5 text-right font-semibold"><NetMillion million={d.institutionMillion} /></td>
      {ORG_DETAIL.map((c, i) => (
        <td key={c.key} className={`py-2 text-right ${orgPad(i, ORG_DETAIL.length)} ${i === 0 ? ORG_EDGE : ""} ${strong ? "font-semibold" : ""}`}>
          <NetMillion million={d.breakdown[c.key]} />
        </td>
      ))}
      <td className={`py-2 pl-5 pr-2.5 text-right ${ORG_EDGE} ${strong ? "font-semibold" : ""}`}>
        <NetMillion million={d.otherCorpMillion} />
      </td>
    </>
  );
  const sum = (f: (d: StockInvestorDay) => number) => days.reduce((acc, d) => acc + f(d), 0);
  const total = {
    individualMillion: sum((d) => d.individualMillion),
    foreignMillion: sum((d) => d.foreignMillion),
    institutionMillion: sum((d) => d.institutionMillion),
    otherCorpMillion: sum((d) => d.otherCorpMillion),
    breakdown: ORG_DETAIL.reduce(
      (acc, c) => ({ ...acc, [c.key]: sum((d) => d.breakdown[c.key]) }),
      {} as StockOrgBreakdown,
    ),
  };

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`최근 ${days.length}일 수급`}
        className="max-h-[85dvh] w-full max-w-5xl overflow-auto rounded-2xl border border-zinc-800 bg-zinc-900 p-5 shadow-xl sm:p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between gap-3">
          <div className="flex flex-wrap items-baseline gap-x-2.5">
            <h3 className="text-lg font-semibold text-zinc-100">최근 {days.length}일 수급</h3>
            <span className="text-xs text-zinc-500">순매수 · 백만원</span>
          </div>
          <button type="button" onClick={onClose} aria-label="닫기" className="rounded-lg p-1 text-zinc-500 hover:bg-zinc-850 hover:text-zinc-200">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden>
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
        {/* 폰에서는 옆으로 밀어 본다 — 첫 열(일자)은 붙어 있다 */}
        <div className="overflow-x-auto">
          <table className="w-full whitespace-nowrap text-xs">
            <thead className="text-zinc-500">
              <tr>
                <th className="sticky left-0 bg-zinc-900 pb-1 pr-3" />
                <th className="px-2.5 pb-1 text-right font-medium">개인</th>
                <th className="px-2.5 pb-1 text-right font-medium">외국인</th>
                <th className="pb-1 pl-2.5 pr-5 text-right font-semibold text-zinc-300">기관계</th>
                <th colSpan={ORG_DETAIL.length} className={`border-b border-zinc-800 pb-1.5 text-center font-medium text-zinc-400 ${ORG_EDGE}`}>
                  기관상세
                </th>
                <th className={`pb-1 pl-5 pr-2.5 text-right font-medium ${ORG_EDGE}`}>기타법인</th>
              </tr>
              <tr>
                <th className="sticky left-0 bg-zinc-900 pb-1.5 pr-3 text-left font-medium">일자</th>
                <th />
                <th />
                <th />
                {ORG_DETAIL.map((c, i) => (
                  <th key={c.key} className={`pb-1.5 pt-1.5 text-right font-medium ${orgPad(i, ORG_DETAIL.length)} ${i === 0 ? ORG_EDGE : ""}`}>
                    {c.label}
                  </th>
                ))}
                <th className={ORG_EDGE} />
              </tr>
            </thead>
            <tbody>
              <tr className="[&>td]:border-t [&>td]:border-zinc-700 [&>td]:bg-zinc-850">
                <td className="sticky left-0 py-2 pr-3 text-left font-bold text-zinc-100">{days.length}일 합계</td>
                {cells(total, true)}
              </tr>
              {days.map((d) => (
                <tr key={d.date} className="[&>td]:border-t [&>td]:border-zinc-800/50 [&>td]:transition-colors hover:[&>td]:bg-zinc-850">
                  <td className="num sticky left-0 bg-zinc-900 py-2 pr-3 text-left text-zinc-400">{dayLabel(d.date)}</td>
                  {cells(d, false)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>,
    document.body,
  );
}
