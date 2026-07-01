import { useMemo } from "react";
import type {
  MarketCloseSnapshotItem,
  MarketType,
  OverseasIndexCloseSnapshotItem,
} from "../../types";

const MARKET_LABEL: Record<MarketType, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };

export interface TimelineDay {
  date: string; // YYYY-MM-DD
  markets: MarketCloseSnapshotItem[];
  indices: OverseasIndexCloseSnapshotItem[]; // 해외지수(나스닥종합 등) 마감
}

/** 지수값 콤마 + 소수 둘째자리. */
function fmtIndex(v: number): string {
  return v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

const hhmm = (iso: string) => iso.slice(11, 16);
const WD = ["일", "월", "화", "수", "목", "금", "토"];

function eok(v: number): string {
  const a = Math.abs(v);
  if (a >= 10000) return `${(a / 10000).toFixed(1)}조`;
  return `${Math.round(a).toLocaleString()}억`;
}
function signed(v: number): string {
  const sign = v > 0 ? "+" : v < 0 ? "-" : "";
  return `${sign}${eok(v)}`;
}
function netClass(v: number): string {
  // 한국 거래소 관행 — 순매수(양수) 빨강 / 순매도(음수) 파랑
  return v > 0 ? "text-red-400" : v < 0 ? "text-blue-400" : "text-zinc-500";
}
function rateClass(rate: number | null): string {
  if (rate === null) return "text-zinc-500";
  return rate >= 0 ? "text-red-400" : "text-blue-400";
}
function rateText(rate: number | null): string {
  if (rate === null) return "";
  return `${rate >= 0 ? "+" : ""}${rate.toFixed(2)}%`;
}

/** 코스피 먼저, 코스닥 다음. */
function ordered(markets: MarketCloseSnapshotItem[]): MarketCloseSnapshotItem[] {
  return [...markets].sort((a) => (a.market === "KOSPI" ? -1 : 1));
}

/** 보는 달 거래일을 위→아래로 이어 붙인 마감 스냅샷 타임라인. 미니 캘린더 선택 시 해당 섹션으로 스크롤. */
export default function TimelineView({
  days,
  selectedDate,
  isLoading,
}: {
  days: TimelineDay[];
  selectedDate: string | null;
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
      {days.map((day) => (
        <DaySection key={day.date} day={day} selected={day.date === selectedDate} />
      ))}
    </div>
  );
}

function DaySection({ day, selected }: { day: TimelineDay; selected: boolean }) {
  const rows = useMemo(() => ordered(day.markets), [day.markets]);
  const [y, m, d] = day.date.split("-").map(Number);
  const wd = WD[new Date(y, m - 1, d).getDay()];
  const total = rows.length + day.indices.length;

  return (
    <section id={`tl-day-${day.date}`} className="scroll-mt-0">
      {/* 날짜 헤더 — 스크롤 중 상단 고정(sticky) */}
      <div className="sticky top-0 z-10 bg-zinc-950 flex items-baseline gap-2 py-2 border-b border-white/[0.08]">
        <h2 className={`text-base font-bold ${selected ? "text-blue-400" : "text-zinc-100"}`}>
          {m}월 {d}일 <span className="font-normal text-zinc-500 text-sm">({wd})</span>
        </h2>
        <span className="text-xs text-zinc-600">{total}건</span>
      </div>

      {total === 0 ? (
        <p className="text-sm text-zinc-600 py-4">기록된 이벤트가 없습니다</p>
      ) : (
        <div className="pt-1">
          {rows.map((mk) => (
            <Row key={mk.market} item={mk} />
          ))}
          {day.indices.map((ix) => (
            <IndexRow key={ix.code} item={ix} />
          ))}
        </div>
      )}
    </section>
  );
}

function IndexRow({ item }: { item: OverseasIndexCloseSnapshotItem }) {
  return (
    <div className="flex items-center gap-3 py-3 px-2 -mx-2 rounded-lg border-b border-white/[0.04] hover:bg-white/[0.03]">
      <span className="num text-xs text-zinc-500 w-11 shrink-0">{hhmm(item.capturedAt)}</span>
      <span
        className={`w-1.5 h-1.5 rounded-full shrink-0 ${
          item.changeRate >= 0 ? "bg-red-400" : "bg-blue-400"
        }`}
      />
      <span className="text-sm text-zinc-200 shrink-0">{item.name}</span>
      <div className="min-w-0 flex-1 num text-sm text-zinc-400">{fmtIndex(item.indexValue)}</div>
      <span className={`num text-sm shrink-0 ${rateClass(item.changeRate)}`}>
        {rateText(item.changeRate)}
      </span>
    </div>
  );
}

function Row({ item }: { item: MarketCloseSnapshotItem }) {
  return (
    <div className="flex items-center gap-3 py-3 px-2 -mx-2 rounded-lg border-b border-white/[0.04] hover:bg-white/[0.03]">
      <span className="num text-xs text-zinc-500 w-11 shrink-0">{hhmm(item.capturedAt)}</span>
      <span
        className={`w-1.5 h-1.5 rounded-full shrink-0 ${
          item.changeRate == null ? "bg-zinc-600" : item.changeRate >= 0 ? "bg-red-400" : "bg-blue-400"
        }`}
      />
      <span className="text-sm text-zinc-200 shrink-0 w-12">{MARKET_LABEL[item.market]}</span>
      <div className="min-w-0 flex-1 flex items-baseline gap-2 text-sm">
        <NetPart label="개인" eok={item.individualEok} />
        <span className="text-zinc-700">·</span>
        <NetPart label="외인" eok={item.foreignEok} />
        <span className="text-zinc-700">·</span>
        <NetPart label="기관" eok={item.institutionEok} />
      </div>
      {item.changeRate !== null && (
        <span className={`num text-sm shrink-0 ${rateClass(item.changeRate)}`}>
          {rateText(item.changeRate)}
        </span>
      )}
    </div>
  );
}

function NetPart({ label, eok }: { label: string; eok: number }) {
  return (
    <span className="shrink-0">
      <span className="text-zinc-500">{label} </span>
      <span className={`num ${netClass(eok)}`}>{signed(eok)}</span>
    </span>
  );
}
