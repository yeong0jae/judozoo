import { useEffect, useRef, useState } from "react";

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
    const col = wd - 1; // 월(1)→0 … 금(5)→4
    if (col === 0 && week.some((x) => x !== null)) {
      weeks.push(week);
      week = Array(5).fill(null);
    }
    week[col] = d;
  }
  if (week.some((x) => x !== null)) weeks.push(week);
  return weeks;
}

/** 거래일(평일) 단일 선택 팝업 달력. value/today/max는 YYYY-MM-DD. */
export default function DatePopover({
  value,
  today,
  onChange,
  onClose,
  align = "right",
}: {
  value: string;
  today: string;
  onChange: (v: string) => void;
  onClose: () => void;
  /** 어느 쪽 끝을 트리거에 맞출지. 달력이 트리거보다 넓어, 맞춘 반대쪽으로 펼쳐진다 */
  align?: "left" | "right";
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [vy, vm] = value.split("-").map(Number);
  const [view, setView] = useState({ year: vy, month: vm - 1 }); // month: 0-index

  // 바깥 클릭·Esc로 닫기
  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [onClose]);

  const shiftMonth = (delta: number) =>
    setView((v) => {
      const d = new Date(v.year, v.month + delta, 1);
      return { year: d.getFullYear(), month: d.getMonth() };
    });

  const weeks = weeksOf(view.year, view.month);

  return (
    <div
      ref={ref}
      className={`absolute ${align === "left" ? "left-0" : "right-0"} z-20 mt-1 w-60 rounded-lg border border-zinc-700 bg-zinc-900 p-3 shadow-xl`}
    >
      <div className="flex items-center justify-between mb-2">
        <span className="text-sm font-semibold text-zinc-100">
          {view.year}년 {view.month + 1}월
        </span>
        <div className="flex gap-1">
          <button
            onClick={() => shiftMonth(-1)}
            className="px-1.5 rounded text-zinc-400 hover:bg-zinc-800"
            aria-label="이전 달"
          >
            ‹
          </button>
          <button
            onClick={() => shiftMonth(1)}
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
              const ds = ymd(view.year, view.month, d);
              const isFuture = ds > today;
              const isSelected = ds === value;
              const isToday = ds === today;
              return (
                <button
                  key={j}
                  disabled={isFuture}
                  onClick={() => {
                    onChange(ds);
                    onClose();
                  }}
                  className={[
                    "h-7 rounded text-xs",
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
