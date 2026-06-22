/** 로컬 오늘 날짜 (YYYY-MM-DD). */
export const todayStr = () => new Date().toISOString().slice(0, 10);

/** 좌우 화살표 + 캘린더로 하루씩 이동. date는 YYYY-MM-DD. */
export default function DateNavigator({
  date,
  onChange,
}: {
  date: string;
  onChange: (v: string) => void;
}) {
  const shift = (days: number) => {
    const d = new Date(date);
    d.setDate(d.getDate() + days);
    onChange(d.toISOString().slice(0, 10));
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
