import NumberFlow from "@number-flow/react";

/** 달러 — 콤마 그룹 + 소수 2자리, 값 변경 시 자릿수가 굴러가는 롤링 애니메이션(NumWon의 USD판). */
export default function NumUsd({
  value,
  className,
  prefix = "$",
}: {
  value: number;
  className?: string;
  prefix?: string; // 기본 "$" — 리스트처럼 기호 없이 쓰려면 prefix="" 전달
}) {
  return (
    <NumberFlow
      value={value}
      locales="en-US"
      prefix={prefix}
      format={{ useGrouping: true, minimumFractionDigits: 2, maximumFractionDigits: 2 }}
      className={className}
    />
  );
}
