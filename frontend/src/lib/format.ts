export function formatKrw(value: number): string {
  return new Intl.NumberFormat("ko-KR").format(Math.round(value)) + "원";
}

export function formatQty(value: number): string {
  return new Intl.NumberFormat("ko-KR").format(value) + "주";
}

export function formatPct(value: number, digits = 2): string {
  const sign = value > 0 ? "+" : "";
  return `${sign}${value.toFixed(digits)}%`;
}

export function formatPrice(value: number): string {
  return new Intl.NumberFormat("ko-KR").format(Math.round(value));
}

export function colorByPnL(value: number): string {
  if (value > 0) return "text-red-400";
  if (value < 0) return "text-blue-400";
  return "text-zinc-300";
}
