import { useMemo } from "react";
import { motion } from "motion/react";
import type { DailyIssueItem } from "../../types";
import type { TimelineDay } from "./TimelineView";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m + 1)}-${pad(d)}`;

/**
 * 큰 월 캘린더 — 테마 캘린더와 같은 7열 풀 그리드 룩. 각 칸에 그날 내가 남긴 이슈를 작게 보여준다.
 * 거래일(평일) 단일 선택(미래 평일도 예정 이슈 작성용으로 선택 허용). 주말은 비활성. 타임라인 좌측 메인 패널용.
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
          // 주말만 비활성 — 미래 평일은 예정 이슈를 적을 수 있게 선택 허용.
          const disabled = wd === 0 || wd === 6;
          return (
            <DayCell
              key={ds}
              day={d}
              issues={byDate.get(ds)?.issues ?? []}
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
  issues,
  isToday,
  isSelected,
  disabled,
  onSelect,
}: {
  day: number;
  issues: DailyIssueItem[];
  isToday: boolean;
  isSelected: boolean;
  disabled: boolean;
  onSelect: () => void;
}) {
  const shown = issues.slice(0, 3);
  const extra = issues.length - shown.length;
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

      <div className="flex flex-col gap-1">
        {shown.map((it) => (
          <div key={it.id} className="flex items-start gap-1">
            <span className="mt-[3px] w-1 h-1 rounded-full bg-amber-400/70 shrink-0" />
            <span className="min-w-0 flex-1 truncate text-[10px] leading-tight text-zinc-300">
              {it.content}
            </span>
          </div>
        ))}
        {extra > 0 && <span className="text-[10px] text-zinc-600">+{extra}개</span>}
      </div>
    </motion.div>
  );
}
