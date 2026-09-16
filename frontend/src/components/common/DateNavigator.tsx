import { useState } from "react";
import DatePopover from "./DatePopover";

/** Date → 로컬 기준 YYYY-MM-DD (toISOString은 UTC라 KST 새벽에 하루 밀림). */
const localStr = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** 로컬 오늘 날짜 (YYYY-MM-DD). */
export const todayStr = () => localStr(new Date());

/** 주말이면 직전 평일로 물러난 날짜. "가장 최근 거래일"의 근사치다.
 *
 * 공휴일은 보정하지 못한다 — 그날은 데이터가 없어 화면이 빈다. 다만 토·일에
 * 빈 화면을 보여주는 것보다는 낫다(대부분의 조회가 주말에 일어난다). */
export function latestTradingDayStr(from: string = todayStr()): string {
  const [y, m, d] = from.split("-").map(Number);
  const dt = new Date(y, m - 1, d);
  while (dt.getDay() === 0 || dt.getDay() === 6) dt.setDate(dt.getDate() - 1);
  return localStr(dt);
}

/** YYYY-MM-DD → "9월 16일" + "(수)" — 요일은 따로 돌려준다(색을 달리 준다). */
const WD = ["일", "월", "화", "수", "목", "금", "토"];
const label = (s: string) => {
  const [y, m, d] = s.split("-").map(Number);
  return { day: `${m}월 ${d}일`, weekday: `(${WD[new Date(y, m - 1, d).getDay()]})` };
};

/** 화살표 — 텍스트 글리프(◀)는 폰트마다 크기·기준선이 달라 옆 글자와 안 맞는다. */
function Chevron({ dir }: { dir: "prev" | "next" }) {
  return (
    <svg
      width="16"
      height="16"
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

/** 좌우 화살표 + 팝업 달력으로 거래일(평일) 이동. date는 YYYY-MM-DD.
 *
 * 세 조각(이전·날짜·다음)을 트랙 하나에 담는다 — 같은 일을 하는 버튼들이라
 * 앱의 다른 세그먼트(`ChangeRateSelector`·`MarketToggle`)와 같은 모양으로 묶는다.
 * "오늘"만 트랙 밖에 둔다. 이동이 아니라 되돌리기라서다. */
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
  const { day, weekday } = label(date);
  // 화살표 칸. inline-flex라야 SVG 아래 기준선 여백이 안 생긴다.
  // 비활성일 때 호버로 밝아지면 누를 수 있는 것처럼 보여, 글자색도 함께 묶어 둔다.
  const stepCls =
    "inline-flex rounded-lg p-1.5 text-zinc-400 transition-colors hover:bg-elevated hover:text-zinc-100 disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-zinc-400";

  return (
    <div className="flex items-center gap-1.5 text-sm">
      <div className="flex items-center rounded-xl bg-zinc-800 p-1">
        <button onClick={() => shift(-1)} className={stepCls} aria-label="이전 거래일">
          <Chevron dir="prev" />
        </button>

        {/* 팝업은 이 칸을 기준으로 뜬다 */}
        <div className="relative">
          <button
            onClick={() => setOpen((o) => !o)}
            className="min-w-[7.25rem] rounded-lg px-2.5 py-1 font-medium text-zinc-100 transition-colors hover:bg-elevated"
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
        className="rounded-lg px-2.5 py-1.5 text-xs text-zinc-400 transition-colors hover:bg-zinc-850 hover:text-zinc-100 disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-zinc-400"
      >
        오늘
      </button>
    </div>
  );
}
