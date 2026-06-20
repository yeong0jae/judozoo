import NumberFlow from "@number-flow/react";

/** 원 단위 정수 — 콤마 그룹, 값 변경 시 자릿수가 굴러가는 롤링 애니메이션. */
export default function NumWon({
  value,
  className,
}: {
  value: number;
  className?: string;
}) {
  return (
    <NumberFlow
      value={value}
      locales="ko-KR"
      format={{ useGrouping: true, maximumFractionDigits: 0 }}
      className={className}
    />
  );
}
