import NumberFlow from "@number-flow/react";

export default function NumUsd({
  value,
  className,
  prefix = "$",
}: {
  value: number;
  className?: string;
  prefix?: string;
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
