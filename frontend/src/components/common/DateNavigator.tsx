import { useState } from "react";
import DatePopover from "./DatePopover";

/** Date → 로컬 기준 YYYY-MM-DD (toISOString은 UTC라 KST 새벽에 하루 밀림). */
const localStr = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** 로컬 오늘 날짜 (YYYY-MM-DD). */
export const todayStr = () => localStr(new Date());

/** YYYY-MM-DD → "M월 D일 (요일)". */
const WD = ["일", "월", "화", "수", "목", "금", "토"];
const label = (s: string) => {
  const [y, m, d] = s.split("-").map(Number);
  const wd = WD[new Date(y, m - 1, d).getDay()];
  return `${m}월 ${d}일 (${wd})`;
};

/** 좌우 화살표 + 팝업 달력으로 거래일(평일) 이동. date는 YYYY-MM-DD. */
export default function DateNavigator({
  date,
  onChange,
}: {
  date: string;
  onChange: (v: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const shift = (days: number) => {
    const [y, m, d] = date.split("-").map(Number);
    const next = new Date(y, m - 1, d + days);
    // 주말은 건너뛰고 같은 방향의 가장 가까운 평일로 이동.
    const step = days > 0 ? 1 : -1;
    while (next.getDay() === 0 || next.getDay() === 6) {
      next.setDate(next.getDate() + step);
    }
    onChange(localStr(next));
  };
  const isToday = date === todayStr();
  return (
    <div className="flex items-center gap-2 text-sm">
      <button
        onClick={() => shift(-1)}
        className="px-2 py-1 rounded text-zinc-400 hover:bg-zinc-800"
        aria-label="이전 날짜"
      >
        ◀
      </button>
      <div className="relative">
        <button
          onClick={() => setOpen((o) => !o)}
          className="bg-zinc-900 border border-zinc-800 rounded px-3 py-1 text-sm text-zinc-200 hover:bg-zinc-800 min-w-[110px]"
        >
          {label(date)}
        </button>
        {open && (
          <DatePopover
            value={date}
            today={todayStr()}
            onChange={onChange}
            onClose={() => setOpen(false)}
          />
        )}
      </div>
      <button
        onClick={() => shift(1)}
        disabled={isToday}
        className="px-2 py-1 rounded text-zinc-400 hover:bg-zinc-800 disabled:opacity-40"
        aria-label="다음 날짜"
      >
        ▶
      </button>
      <button
        onClick={() => onChange(todayStr())}
        disabled={isToday}
        className="px-2 py-1 text-xs rounded text-zinc-400 hover:bg-zinc-800 disabled:opacity-40"
      >
        오늘
      </button>
    </div>
  );
}
