const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m + 1)}-${pad(d)}`;
const WEEKDAYS = ["월", "화", "수", "목", "금"];

/** 그 달의 평일(월~금)만 주 단위 5열로 배치. 빈칸은 null. */
function weeksOf(year: number, month: number): (number | null)[][] {
  const last = new Date(year, month + 1, 0).getDate();
  const weeks: (number | null)[][] = [];
  let week: (number | null)[] = Array(5).fill(null);
  for (let d = 1; d <= last; d++) {
    const wd = new Date(year, month, d).getDay(); // 0=일 … 6=토
    if (wd === 0 || wd === 6) continue;
    const col = wd - 1;
    if (col === 0 && week.some((x) => x !== null)) {
      weeks.push(week);
      week = Array(5).fill(null);
    }
    week[col] = d;
  }
  if (week.some((x) => x !== null)) weeks.push(week);
  return weeks;
}

/** 인라인 미니 월 캘린더 — 거래일(평일) 단일 선택. 좌측 패널용. */
export default function MiniMonthCalendar({
  month,
  selected,
  today,
  onShiftMonth,
  onSelect,
}: {
  month: Date;
  selected: string | null;
  today: string;
  onShiftMonth: (delta: number) => void;
  onSelect: (date: string) => void;
}) {
  const y = month.getFullYear();
  const m = month.getMonth();
  const weeks = weeksOf(y, m);

  return (
    <div className="rounded-2xl border border-white/[0.04] bg-zinc-900 p-4">
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm font-semibold text-zinc-100">
          {y}년 {m + 1}월
        </span>
        <div className="flex gap-1">
          <button
            onClick={() => onShiftMonth(-1)}
            className="px-1.5 rounded text-zinc-400 hover:bg-zinc-800"
            aria-label="이전 달"
          >
            ‹
          </button>
          <button
            onClick={() => onShiftMonth(1)}
            className="px-1.5 rounded text-zinc-400 hover:bg-zinc-800"
            aria-label="다음 달"
          >
            ›
          </button>
        </div>
      </div>

      <div className="grid grid-cols-5 gap-1 text-center text-[11px] text-zinc-500 mb-1">
        {WEEKDAYS.map((w) => (
          <div key={w}>{w}</div>
        ))}
      </div>

      <div className="space-y-1">
        {weeks.map((week, i) => (
          <div key={i} className="grid grid-cols-5 gap-1">
            {week.map((d, j) => {
              if (d === null) return <div key={j} />;
              const ds = ymd(y, m, d);
              const isFuture = ds > today;
              const isSelected = ds === selected;
              const isToday = ds === today;
              return (
                <button
                  key={j}
                  disabled={isFuture}
                  onClick={() => onSelect(ds)}
                  className={[
                    "h-8 rounded-lg text-xs num",
                    isFuture
                      ? "text-zinc-700 cursor-not-allowed"
                      : "hover:bg-zinc-800",
                    isSelected
                      ? "bg-blue-600 text-white"
                      : isToday
                        ? "text-blue-400 font-semibold"
                        : "text-zinc-300",
                  ].join(" ")}
                >
                  {d}
                </button>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}
