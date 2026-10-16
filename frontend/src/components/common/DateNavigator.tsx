import { useState } from "react";
import DatePopover from "./DatePopover";

const localStr = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

export const todayStr = () => localStr(new Date());

export function latestTradingDayStr(from: string = todayStr()): string {
  const [y, m, d] = from.split("-").map(Number);
  const dt = new Date(y, m - 1, d);
  while (dt.getDay() === 0 || dt.getDay() === 6) dt.setDate(dt.getDate() - 1);
  return localStr(dt);
}

const WD = ["일", "월", "화", "수", "목", "금", "토"];
const label = (s: string) => {
  const [y, m, d] = s.split("-").map(Number);
  return { day: `${m}월 ${d}일`, weekday: `(${WD[new Date(y, m - 1, d).getDay()]})` };
};

function Chevron({ dir }: { dir: "prev" | "next" }) {
  return (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d={dir === "prev" ? "M15 6l-6 6 6 6" : "M9 6l6 6-6 6"} />
    </svg>
  );
}

export default function DateNavigator({
  date,
  onChange,
  popoverAlign,
}: {
  date: string;
  onChange: (v: string) => void;
  popoverAlign?: "left" | "right";
}) {
  const [open, setOpen] = useState(false);
  const shift = (days: number) => {
    const [y, m, d] = date.split("-").map(Number);
    const next = new Date(y, m - 1, d + days);
    const step = days > 0 ? 1 : -1;
    while (next.getDay() === 0 || next.getDay() === 6) {
      next.setDate(next.getDate() + step);
    }
    onChange(localStr(next));
  };
  const isToday = date === todayStr();
  const { day, weekday } = label(date);
  const stepCls =
    "inline-flex rounded-[7px] p-[6px] text-zinc-400 transition-colors hover:bg-elevated hover:text-zinc-100 disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-zinc-400";

  return (
    <div className="flex items-center gap-[6px] text-[14px]">
      <div className="flex items-center rounded-[10px] bg-zinc-800 p-[3px]">
        <button onClick={() => shift(-1)} className={stepCls} aria-label="이전 거래일">
          <Chevron dir="prev" />
        </button>

        <div className="relative">
          <button
            onClick={() => setOpen((o) => !o)}
            className="min-w-[104px] rounded-[7px] px-[9px] py-[4px] font-medium text-zinc-100 transition-colors hover:bg-elevated"
            aria-haspopup="dialog"
            aria-expanded={open}
          >
            {day} <span className="text-zinc-400">{weekday}</span>
          </button>
          {open && (
            <DatePopover
              value={date}
              today={todayStr()}
              onChange={onChange}
              onClose={() => setOpen(false)}
              align={popoverAlign}
            />
          )}
        </div>

        <button
          onClick={() => shift(1)}
          disabled={isToday}
          className={stepCls}
          aria-label="다음 거래일"
        >
          <Chevron dir="next" />
        </button>
      </div>

      <button
        onClick={() => onChange(todayStr())}
        disabled={isToday}
        className="rounded-[7px] px-[9px] py-[5px] text-[12.5px] text-zinc-400 transition-colors hover:bg-zinc-850 hover:text-zinc-100 disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-zinc-400"
      >
        오늘
      </button>
    </div>
  );
}
