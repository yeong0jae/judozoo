import NumberFlow from "@number-flow/react";

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
