import { useMemo } from "react";
import { motion } from "motion/react";
import type {
  MarketCloseSnapshotItem,
  MarketType,
  OverseasIndexCloseSnapshotItem,
} from "../../types";
import type { TimelineDay } from "./TimelineView";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
const MARKET_LABEL: Record<MarketType, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };
const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m + 1)}-${pad(d)}`;

function rateClass(rate: number): string {
  return rate >= 0 ? "text-red-400" : "text-blue-400";
}
function rateText(rate: number): string {
  return `${rate >= 0 ? "+" : ""}${rate.toFixed(2)}%`;
}

function eok(v: number): string {
  const a = Math.abs(v);
  if (a >= 10000) return `${(a / 10000).toFixed(1)}조`;
  return `${Math.round(a).toLocaleString()}억`;
}
function signed(v: number): string {
  return `${v > 0 ? "+" : v < 0 ? "-" : ""}${eok(v)}`;
}
function netClass(v: number): string {
  // 한국 거래소 관행 — 순매수(양수) 빨강 / 순매도(음수) 파랑
  return v > 0 ? "text-red-400" : v < 0 ? "text-blue-400" : "text-zinc-500";
}

/** 코스피 먼저, 코스닥 다음. */
function ordered(markets: MarketCloseSnapshotItem[]): MarketCloseSnapshotItem[] {
  return [...markets].sort((a) => (a.market === "KOSPI" ? -1 : 1));
}

/**
 * 큰 월 캘린더 — 테마 캘린더와 같은 7열 풀 그리드 룩. 각 칸에 그날 코스피/코스닥 마감 순매수(개인·기관·외인).
 * 거래일(평일) 단일 선택. 주말·미래는 비활성. 타임라인 좌측 메인 패널용.
 */
export default function MonthCalendar({
  month,
  selected,
  today,
  byDate,
  onShiftMonth,
  onSelect,
}: {
  month: Date;
  selected: string | null;
  today: string;
  byDate: Map<string, TimelineDay>;
  onShiftMonth: (delta: number) => void;
  onSelect: (date: string) => void;
}) {
  const y = month.getFullYear();
  const m = month.getMonth();
  const monthEnd = new Date(y, m + 1, 0);

  const cells = useMemo(() => {
    const lead = new Date(y, m, 1).getDay(); // 0=일
    const rows = Math.ceil((lead + monthEnd.getDate()) / 7) * 7;
    return Array.from({ length: rows }, (_, i) => {
      const dayNum = i - lead + 1;
      return dayNum < 1 || dayNum > monthEnd.getDate() ? null : dayNum;
    });
  }, [y, m, monthEnd]);

  return (
    <div>
      <div className="flex items-center gap-3 mb-3">
        <span className="text-base font-semibold tabular-nums text-zinc-100">
          {y}년 {m + 1}월
        </span>
        <div className="flex gap-1">
          <motion.button
            onClick={() => onShiftMonth(-1)}
            whileTap={{ scale: 0.85 }}
            className="px-2 py-0.5 rounded text-zinc-400 hover:bg-zinc-800"
            aria-label="이전 달"
          >
            ‹
          </motion.button>
          <motion.button
            onClick={() => onShiftMonth(1)}
            whileTap={{ scale: 0.85 }}
            className="px-2 py-0.5 rounded text-zinc-400 hover:bg-zinc-800"
            aria-label="다음 달"
          >
            ›
          </motion.button>
        </div>
      </div>

      <div className="grid grid-cols-7">
        {WEEKDAYS.map((w, i) => (
          <div
            key={w}
            className={`pb-2 text-xs font-medium border-b border-white/[0.08] ${
              i === 0 ? "text-red-400" : i === 6 ? "text-blue-400" : "text-zinc-400"
            }`}
          >
            {w}
          </div>
        ))}
      </div>

      <div className="grid grid-cols-7 gap-1.5 pt-1.5">
        {cells.map((d, i) => {
          if (d === null) return <div key={`empty-${i}`} className="min-h-28" />;
          const ds = ymd(y, m, d);
          const wd = new Date(y, m, d).getDay();
          const disabled = wd === 0 || wd === 6 || ds > today;
          const dayData = byDate.get(ds);
          return (
            <DayCell
              key={ds}
              day={d}
              markets={dayData?.markets ?? []}
              indices={dayData?.indices ?? []}
              isToday={ds === today}
              isSelected={ds === selected}
              disabled={disabled}
              onSelect={() => onSelect(ds)}
            />
          );
        })}
      </div>
    </div>
  );
}

function DayCell({
  day,
  markets,
  indices,
  isToday,
  isSelected,
  disabled,
  onSelect,
}: {
  day: number;
  markets: MarketCloseSnapshotItem[];
  indices: OverseasIndexCloseSnapshotItem[];
  isToday: boolean;
  isSelected: boolean;
  disabled: boolean;
  onSelect: () => void;
}) {
  return (
    <motion.div
      onClick={disabled ? undefined : onSelect}
      whileTap={disabled ? undefined : { scale: 0.98 }}
      className={`min-h-28 rounded-xl p-2 flex flex-col gap-1.5 transition-colors ${
        disabled ? "cursor-default" : "cursor-pointer"
      } ${
        isSelected
          ? "ring-1 ring-inset ring-blue-600 bg-zinc-900"
          : isToday
            ? "ring-1 ring-inset ring-zinc-700 bg-zinc-900/60"
            : disabled
              ? ""
              : "hover:bg-zinc-900/60"
      }`}
    >
      <span
        className={`text-xs num ${
          isToday ? "text-blue-400 font-semibold" : disabled ? "text-zinc-700" : "text-zinc-500"
        }`}
      >
        {day}
        {isToday && <span className="ml-1 text-[10px]">오늘</span>}
      </span>

      <div className="flex flex-col gap-1.5">
        {ordered(markets).map((mk) => (
          <div key={mk.market} className="flex flex-col gap-0.5">
            <span className="text-[10px] font-medium text-zinc-400">{MARKET_LABEL[mk.market]}</span>
            <div className="flex flex-wrap gap-x-1.5 text-[10px] num leading-tight">
              <NetPart label="개" eok={mk.individualEok} />
              <NetPart label="외" eok={mk.foreignEok} />
              <NetPart label="기" eok={mk.institutionEok} />
            </div>
          </div>
        ))}
        {indices.map((ix) => (
          <div key={ix.code} className="flex items-baseline gap-1 text-[10px] num leading-tight">
            <span className="font-medium text-zinc-400">{ix.name}</span>
            <span className={rateClass(ix.changeRate)}>{rateText(ix.changeRate)}</span>
          </div>
        ))}
      </div>
    </motion.div>
  );
}

function NetPart({ label, eok: v }: { label: string; eok: number }) {
  return (
    <span className="whitespace-nowrap">
      <span className="text-zinc-600">{label}</span>
      <span className={netClass(v)}>{signed(v)}</span>
    </span>
  );
}
