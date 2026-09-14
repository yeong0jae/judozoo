import { useId } from "react";
import { motion } from "motion/react";

// 당일 등락률 임계값 선택지 — -12 하한 + -7~+7 홀수 구간 + 0
export const CHANGE_RATE_OPTIONS = [-12, -7, -5, -3, 0, 3, 5, 7];

// 한국 거래소 관행 — 상승(양수) 빨강 / 하락(음수) 파랑 / 0 중립
function thumbTone(rate: number): string {
  if (rate > 0) return "bg-red-500/15";
  if (rate < 0) return "bg-blue-500/15";
  return "bg-zinc-950";
}
function textTone(rate: number, active: boolean): string {
  if (rate > 0) return active ? "text-red-700" : "text-red-600/70";
  if (rate < 0) return active ? "text-blue-700" : "text-blue-600/70";
  return active ? "text-zinc-100" : "text-zinc-400";
}

/**
 * 당일 등락률 임계값 선택 — 소프트 세그먼트. 활성 표시(썸)가 버튼 사이를 슬라이드하며
 * 부호색(상승 빨강/하락 파랑/0 중립)으로 물든다. [options]로 선택지 주입 가능.
 */
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
    <div className="flex items-center gap-2 flex-wrap justify-end">
      <span className="text-xs text-zinc-500">등락률</span>
      <div className="flex rounded-xl bg-zinc-800 p-1 text-xs num">
        {options.map((rate) => {
          const active = rate === value;
          return (
            <motion.button
              key={rate}
              type="button"
              onClick={() => onChange(rate)}
              whileTap={{ scale: 0.92 }}
              className="relative px-2.5 py-1 rounded-lg"
              aria-pressed={active}
            >
              {active && (
                <motion.span
                  layoutId={`${id}-active`}
                  className={`absolute inset-0 rounded-lg ${thumbTone(rate)}`}
                  transition={{ type: "spring", stiffness: 500, damping: 35 }}
                />
              )}
              <span className={`relative z-10 font-medium transition-colors ${textTone(rate, active)}`}>
                {rate > 0 ? `+${rate}` : rate}
              </span>
            </motion.button>
          );
        })}
      </div>
      <span className="text-xs text-zinc-500">% 이상</span>
    </div>
  );
}
