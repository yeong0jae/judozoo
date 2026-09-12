import { useMemo } from "react";
import type {
  MarketCloseSnapshotItem,
  MarketType,
  OverseasIndexCloseSnapshotItem,
} from "../../types";

const MARKET_LABEL: Record<MarketType, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };
const WD = ["일", "월", "화", "수", "목", "금", "토"];

export interface TimelineDay {
  date: string; // YYYY-MM-DD
  markets: MarketCloseSnapshotItem[];
  indices: OverseasIndexCloseSnapshotItem[]; // 해외지수(나스닥종합 등) 마감
}

/** 지수값 콤마 + 소수 둘째자리. */
function fmtIndex(v: number): string {
  return v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
function eok(v: number): string {
  const a = Math.abs(v);
  if (a >= 10000) return `${(a / 10000).toFixed(1)}조`;
  return `${Math.round(a).toLocaleString()}억`;
}
function signed(v: number): string {
  const sign = v > 0 ? "+" : v < 0 ? "-" : "";
  return `${sign}${eok(v)}`;
}
// 한국 거래소 관행 — 순매수(양)/상승 빨강, 순매도(음)/하락 파랑
function netClass(v: number): string {
  return v > 0 ? "text-red-400" : v < 0 ? "text-blue-400" : "text-zinc-500";
}
function upDown(rate: number | null): string {
  if (rate === null) return "text-zinc-400";
  return rate >= 0 ? "text-red-400" : "text-blue-400";
}
function rateText(rate: number | null): string {
  if (rate === null) return "";
  return `${rate >= 0 ? "+" : ""}${rate.toFixed(2)}%`;
}
function ordered(markets: MarketCloseSnapshotItem[]): MarketCloseSnapshotItem[] {
  return [...markets].sort((a) => (a.market === "KOSPI" ? -1 : 1));
}
function parts(date: string) {
  const [y, m, d] = date.split("-").map(Number);
  return { m, d, wd: WD[new Date(y, m - 1, d).getDay()] };
}

/**
 * 거래일 타임라인 — 위→아래로 과거 → 오늘 → 예정(미래), 날짜 오름차순. 미니 캘린더 선택 시 해당 섹션으로 스크롤.
 * 과거·오늘은 마감 스냅샷 + 이슈, 미래는 마감 데이터가 없어 이슈만(빈 날은 슬림 행) 노출한다.
 */
export default function TimelineView({
  days,
  selectedDate,
  today,
  isLoading,
}: {
  days: TimelineDay[];
  selectedDate: string | null;
  today: string;
  isLoading: boolean;
}) {
  if (isLoading && days.every((d) => d.markets.length === 0 && d.indices.length === 0)) {
    return (
      <div className="rounded-2xl border border-white/[0.04] bg-zinc-900 p-8 text-sm text-zinc-500">
        불러오는 중…
      </div>
    );
  }
  return (
    <div className="space-y-6">
      {days.map((day) => {
        if (day.date > today) {
          // 미래는 빈 날을 줄줄이 늘어놓지 않는다 — 이슈가 있거나 달력에서 고른 날만 노출.
          if (day.date !== selectedDate) return null;
          return <FutureDay key={day.date} day={day} selected={day.date === selectedDate} />;
        }
        return (
          <DaySection
            key={day.date}
            day={day}
            selected={day.date === selectedDate}
            today={day.date === today}
          />
        );
      })}
    </div>
  );
}

function DaySection({ day, selected, today }: { day: TimelineDay; selected: boolean; today: boolean }) {
  const rows = useMemo(() => ordered(day.markets), [day.markets]);
  const { m, d, wd } = parts(day.date);

  return (
    <section id={`tl-day-${day.date}`} className="scroll-mt-0">
      <div className="sticky top-0 z-10 bg-zinc-950 flex items-center justify-between gap-2 py-2 border-b border-white/[0.08]">
        <div className="flex items-baseline gap-2">
          <h2 className={`text-base font-bold ${selected ? "text-blue-400" : "text-zinc-100"}`}>
            {m}월 {d}일 <span className="font-normal text-zinc-500 text-sm">({wd})</span>
          </h2>
          {today && <span className="text-xs font-medium text-blue-400">오늘</span>}
        </div>
        <div className="flex flex-wrap items-center justify-end gap-1">
          {rows.map((mk) => (
            <Chip key={mk.market} label={MARKET_LABEL[mk.market]} rate={mk.changeRate} />
          ))}
          {day.indices.map((ix) => (
            <Chip key={ix.code} label={ix.name} rate={ix.changeRate} />
          ))}
        </div>
      </div>

      <div className="pt-1">
        {rows.map((mk) => (
          <FlowRow key={mk.market} item={mk} />
        ))}
        {day.indices.map((ix) => (
          <IndexRow key={ix.code} item={ix} />
        ))}
      </div>
    </section>
  );
}

/**
 * 미래 거래일 — 마감 데이터 없음. 이슈가 있거나 달력에서 선택한 날만 렌더되며(빈 미래는 목록에서 제외),
 * 예정 이슈 영역을 펼쳐 보여준다. 이슈가 아직 없으면 입력창에 바로 포커스한다.
 */
function FutureDay({ day, selected }: { day: TimelineDay; selected: boolean }) {
  const { m, d, wd } = parts(day.date);
  return (
    <section id={`tl-day-${day.date}`} className="scroll-mt-0">
      <div className="sticky top-0 z-10 bg-zinc-950 flex items-center gap-2 py-2 border-b border-white/[0.08]">
        <h2 className={`text-base font-bold ${selected ? "text-blue-400" : "text-zinc-300"}`}>
          {m}월 {d}일 <span className="font-normal text-zinc-500 text-sm">({wd})</span>
        </h2>
        <span className="text-[11px] font-medium text-amber-300/80">예정</span>
      </div>
    </section>
  );
}

function Chip({ label, rate }: { label: string; rate: number | null }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-md bg-white/[0.04] px-1.5 py-0.5 text-[11px]">
      <span className="text-zinc-400">{label}</span>
      <span className={`num font-semibold ${upDown(rate)}`}>{rateText(rate)}</span>
    </span>
  );
}

function FlowRow({ item }: { item: MarketCloseSnapshotItem }) {
  return (
    <div className="flex items-center gap-3 py-2.5 px-2 -mx-2 rounded-lg border-b border-white/[0.04] hover:bg-white/[0.03]">
      <span
        className={`w-1.5 h-1.5 rounded-full shrink-0 ${
          item.changeRate == null ? "bg-zinc-600" : item.changeRate >= 0 ? "bg-red-400" : "bg-blue-400"
        }`}
      />
      <span className="text-sm text-zinc-200 shrink-0 w-12">{MARKET_LABEL[item.market]}</span>
      <div className="min-w-0 flex-1 flex items-baseline gap-2 text-sm">
        <Net label="개인" v={item.individualEok} />
        <span className="text-zinc-700">·</span>
        <Net label="외인" v={item.foreignEok} />
        <span className="text-zinc-700">·</span>
        <Net label="기관" v={item.institutionEok} />
      </div>
      {item.indexValue != null && (
        <span className="num text-sm shrink-0 text-zinc-400">{fmtIndex(item.indexValue)}</span>
      )}
      {item.changeRate !== null && (
        <span className={`num text-sm shrink-0 ${upDown(item.changeRate)}`}>{rateText(item.changeRate)}</span>
      )}
    </div>
  );
}

function IndexRow({ item }: { item: OverseasIndexCloseSnapshotItem }) {
  return (
    <div className="flex items-center gap-3 py-2.5 px-2 -mx-2 rounded-lg border-b border-white/[0.04] hover:bg-white/[0.03]">
      <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${item.changeRate >= 0 ? "bg-red-400" : "bg-blue-400"}`} />
      <span className="text-sm text-zinc-200 shrink-0 w-12">{item.name}</span>
      <div className="min-w-0 flex-1 num text-sm text-zinc-400">{fmtIndex(item.indexValue)}</div>
      <span className={`num text-sm shrink-0 ${upDown(item.changeRate)}`}>{rateText(item.changeRate)}</span>
    </div>
  );
}

function Net({ label, v }: { label: string; v: number }) {
  return (
    <span className="shrink-0">
      <span className="text-zinc-500">{label} </span>
      <span className={`num ${netClass(v)}`}>{signed(v)}</span>
    </span>
  );
}
