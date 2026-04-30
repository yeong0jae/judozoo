export function formatKrw(value) {
    return new Intl.NumberFormat("ko-KR").format(Math.round(value)) + "원";
}
export function formatQty(value) {
    return new Intl.NumberFormat("ko-KR").format(value) + "주";
}
export function formatPct(value, digits = 2) {
    const sign = value > 0 ? "+" : "";
    return `${sign}${value.toFixed(digits)}%`;
}
export function formatPrice(value) {
    return new Intl.NumberFormat("ko-KR").format(Math.round(value));
}
export function colorByPnL(value) {
    if (value > 0)
        return "text-red-400";
    if (value < 0)
        return "text-blue-400";
    return "text-zinc-300";
}
