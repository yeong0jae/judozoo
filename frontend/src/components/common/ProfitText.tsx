import { colorByPnL } from "../../lib/format";

interface Props {
  value: number | null;
  format: (v: number) => string;
  className?: string;
  zeroAsDash?: boolean;
}

export default function ProfitText({
  value,
  format,
  className = "",
  zeroAsDash = false,
}: Props) {
  if (value === null || (zeroAsDash && value === 0)) {
    return <span className="text-zinc-500">-</span>;
  }
  return (
    <span className={`${colorByPnL(value)} ${className}`}>{format(value)}</span>
  );
}
