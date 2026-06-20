// 당일 등락률 임계값 선택지 — -7 ~ +7 중 홀수 구간 + 0
export const CHANGE_RATE_OPTIONS = [-7, -5, -3, 0, 3, 5, 7];

/** 당일 등락률 임계값 선택 — -7~7% 세그먼트 버튼. 주도주·돌파 레이더 공용. */
export default function ChangeRateSelector({
  value,
  onChange,
}: {
  value: number;
  onChange: (rate: number) => void;
}) {
  return (
    <div className="flex items-center gap-1.5 flex-wrap justify-end">
      <span className="text-xs text-zinc-500">등락률</span>
      <div className="inline-flex flex-wrap rounded-md overflow-hidden border border-zinc-700">
        {CHANGE_RATE_OPTIONS.map((rate) => (
          <button
            key={rate}
            type="button"
            onClick={() => onChange(rate)}
            className={`px-2 py-0.5 text-xs font-medium border-l border-zinc-700 first:border-l-0 transition-colors ${
              rate === value
                ? "bg-emerald-600 text-white"
                : "bg-zinc-900 text-zinc-400 hover:bg-zinc-800"
            }`}
            aria-pressed={rate === value}
          >
            {rate > 0 ? `+${rate}` : rate}
          </button>
        ))}
        <span className="px-1.5 py-0.5 text-xs text-zinc-500 bg-zinc-900 border-l border-zinc-700">
          %
        </span>
      </div>
    </div>
  );
}
