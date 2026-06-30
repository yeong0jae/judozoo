import { useMemo } from "react";
import { motion } from "motion/react";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m + 1)}-${pad(d)}`;

/**
 * 큰 월 캘린더 — 테마 캘린더와 같은 7열 그리드 룩. 거래일(평일) 단일 선택.
 * 주말·미래는 비활성, 데이터 있는 날은 점으로 표시. 타임라인 좌측 패널용.
 */
export default function MonthCalendar({
  month,
  selected,
  today,
  datesWithData,
  onShiftMonth,
  onSelect,
}: {
  month: Date;
  selected: string | null;
  today: string;
  datesWithData: Set<string>;
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
    <div className="rounded-2xl border border-white/[0.04] bg-zinc-900 p-4">
      <div className="flex items-center justify-between mb-3">
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
            className={`pb-2 text-center text-xs font-medium border-b border-white/[0.08] ${
              i === 0 ? "text-red-400" : i === 6 ? "text-blue-400" : "text-zinc-400"
            }`}
          >
            {w}
          </div>
        ))}
      </div>

      <div className="grid grid-cols-7 gap-1.5 pt-1.5">
        {cells.map((d, i) => {
          if (d === null) return <div key={`empty-${i}`} className="h-12" />;
          const ds = ymd(y, m, d);
          const wd = new Date(y, m, d).getDay();
          const isWeekend = wd === 0 || wd === 6;
          const isFuture = ds > today;
          const disabled = isWeekend || isFuture;
          const isSelected = ds === selected;
          const isToday = ds === today;
          const hasData = datesWithData.has(ds);
          return (
            <motion.button
              key={ds}
              disabled={disabled}
              onClick={() => onSelect(ds)}
              whileTap={disabled ? undefined : { scale: 0.96 }}
              className={[
                "relative h-12 rounded-xl text-sm num flex items-center justify-center transition-colors",
                disabled
                  ? "text-zinc-700 cursor-not-allowed"
                  : "hover:bg-zinc-800 cursor-pointer",
                isSelected
                  ? "bg-blue-600 text-white"
                  : isToday
                    ? "text-blue-400 font-semibold ring-1 ring-inset ring-zinc-700"
                    : !disabled
                      ? "text-zinc-300"
                      : "",
              ].join(" ")}
            >
              {d}
              {hasData && (
                <span
                  className={`absolute bottom-1.5 w-1 h-1 rounded-full ${
                    isSelected ? "bg-white/80" : "bg-amber-400/90"
                  }`}
                />
              )}
            </motion.button>
          );
        })}
      </div>
    </div>
  );
}
