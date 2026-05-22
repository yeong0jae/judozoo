import { useKospiIndex } from "../../api/queries";

/**
 * 헤더의 KOSPI 종합 지수 한 줄 배지.
 * "KOSPI 7,847.71 +0.41%" — 등락률은 한국 거래소 관행(양수 빨강 / 음수 파랑).
 */
export default function KospiIndexBadge() {
  const { data, isLoading } = useKospiIndex();
  if (isLoading || !data) {
    return (
      <div className="px-2.5 py-1 text-xs text-zinc-500" aria-label="KOSPI 로딩 중">
        KOSPI …
      </div>
    );
  }

  const { currentValue, changeRate } = data;
  const tone =
    changeRate > 0
      ? "text-red-600"
      : changeRate < 0
        ? "text-blue-600"
        : "text-zinc-400";
  const sign = changeRate > 0 ? "+" : "";

  return (
    <div className="flex items-baseline gap-1.5 px-2.5 py-1 text-xs">
      <span className="text-zinc-500">KOSPI</span>
      <span className="text-zinc-200 font-medium tabular-nums">
        {currentValue.toLocaleString("ko-KR", {
          minimumFractionDigits: 2,
          maximumFractionDigits: 2,
        })}
      </span>
      <span className={`${tone} font-medium tabular-nums`}>
        {sign}
        {changeRate.toFixed(2)}%
      </span>
    </div>
  );
}
