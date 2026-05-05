import { colorByPnL } from "../../lib/format";

interface Props {
  value: number;
  format: (v: number) => string;
  className?: string;
  // value === 0 일 때 "-" 로 표기. 종료 직후 등 의미 없는 0 표현용.
  zeroAsDash?: boolean;
}

export default function ProfitText({
  value,
  format,
  className = "",
  zeroAsDash = false,
}: Props) {
  if (zeroAsDash && value === 0) {
    return <span className="text-zinc-500">-</span>;
  }
  return (
    <span className={`${colorByPnL(value)} ${className}`}>{format(value)}</span>
  );
}
