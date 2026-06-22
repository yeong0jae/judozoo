/** Date → 로컬 기준 YYYY-MM-DD (toISOString은 UTC라 KST 새벽에 하루 밀림). */
const localStr = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** 로컬 오늘 날짜 (YYYY-MM-DD). */
export const todayStr = () => localStr(new Date());

/** 좌우 화살표 + 캘린더로 하루씩 이동. date는 YYYY-MM-DD. */
export default function DateNavigator({
  date,
  onChange,
}: {
  date: string;
  onChange: (v: string) => void;
}) {
  const shift = (days: number) => {
    const [y, m, d] = date.split("-").map(Number);
    onChange(localStr(new Date(y, m - 1, d + days)));
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
      <input
        type="date"
        value={date}
        max={todayStr()}
        onChange={(e) => onChange(e.target.value)}
        className="bg-zinc-900 border border-zinc-800 rounded px-2 py-1 text-sm text-zinc-200"
      />
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
