import { useId } from "react";
import { motion } from "motion/react";

// 당일 등락률 임계값 선택지 — -7 ~ +7 중 홀수 구간 + 0
export const CHANGE_RATE_OPTIONS = [-7, -5, -3, 0, 3, 5, 7];

/** 당일 등락률 임계값 선택 — 세그먼트 버튼. 활성 표시가 버튼 사이를 슬라이드. [options]로 선택지 주입 가능. */
export default function ChangeRateSelector({
  value,
  onChange,
  options = CHANGE_RATE_OPTIONS,
}: {
  value: number;
  onChange: (rate: number) => void;
  options?: number[];
}) {
  const id = useId();
  return (
    <div className="flex items-center gap-1.5 flex-wrap justify-end">
      <span className="text-xs text-zinc-500">등락률</span>
      <div className="inline-flex flex-wrap rounded-md overflow-hidden border border-zinc-700">
        {options.map((rate) => {
          const active = rate === value;
          return (
            <motion.button
              key={rate}
              type="button"
              onClick={() => onChange(rate)}
              whileTap={{ scale: 0.92 }}
              className="relative px-2 py-0.5 text-xs font-medium border-l border-zinc-700 first:border-l-0"
              aria-pressed={active}
            >
              {active && (
                <motion.span
                  layoutId={`${id}-active`}
                  className="absolute inset-0 bg-emerald-600"
                  transition={{ type: "spring", stiffness: 500, damping: 35 }}
                />
              )}
              <span
                className={`relative z-10 transition-colors ${
                  active ? "text-white" : "text-zinc-400"
                }`}
              >
                {rate > 0 ? `+${rate}` : rate}
              </span>
            </motion.button>
          );
        })}
        <span className="px-1.5 py-0.5 text-xs text-zinc-500 bg-zinc-900 border-l border-zinc-700">
          %
        </span>
      </div>
    </div>
  );
}
