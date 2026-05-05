// === Currency / Quantity ===
export function formatKRW(value) {
    return new Intl.NumberFormat("ko-KR").format(Math.round(value)) + "원";
}
// 기존 호출부 호환
export const formatKrw = formatKRW;
export function formatPrice(value) {
    return new Intl.NumberFormat("ko-KR").format(Math.round(value));
}
export function formatQty(value) {
    return new Intl.NumberFormat("ko-KR").format(value) + "주";
}
// === Percentage ===
// 입력은 소수 (rate). 0.025 → "+2.50%".
export function formatPct(rate, digits = 2) {
    const pct = rate * 100;
    const sign = pct > 0 ? "+" : "";
    return `${sign}${pct.toFixed(digits)}%`;
}
// === Date / Time ===
export function formatDateTime(iso) {
    // "2026-05-05T14:23:45" → "2026-05-05 14:23:45"
    return iso.slice(0, 10) + " " + iso.slice(11, 19);
}
export function formatTime(iso) {
    return iso.slice(11, 16); // HH:mm
}
export function formatDuration(startIso, endIso) {
    const ms = new Date(endIso).getTime() - new Date(startIso).getTime();
    if (Number.isNaN(ms) || ms < 0)
        return "-";
    const totalSec = Math.floor(ms / 1000);
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = totalSec % 60;
    if (h > 0)
        return `${h}h ${m}m`;
    if (m > 0)
        return `${m}m`;
    return `${s}s`;
}
export function formatRelative(iso, now = new Date()) {
    const ms = now.getTime() - new Date(iso).getTime();
    if (Number.isNaN(ms))
        return "-";
    if (ms < 5_000)
        return "방금";
    const sec = Math.floor(ms / 1000);
    if (sec < 60)
        return `${sec}초 전`;
    const min = Math.floor(sec / 60);
    if (min < 60)
        return `${min}분 전`;
    const hr = Math.floor(min / 60);
    if (hr < 24)
        return `${hr}시간 전`;
    const day = Math.floor(hr / 24);
    return `${day}일 전`;
}
// === Color ===
// 한국 거래소 관행: 양수 빨강, 음수 파랑
export function colorByPnL(value) {
    if (value > 0)
        return "text-red-400";
    if (value < 0)
        return "text-blue-400";
    return "text-zinc-300";
}
