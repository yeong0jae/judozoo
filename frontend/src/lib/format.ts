// === Currency / Quantity ===
export function formatKRW(value: number): string {
  return new Intl.NumberFormat("ko-KR").format(Math.round(value)) + "원";
}

// 기존 호출부 호환
export const formatKrw = formatKRW;

// 한국식 단위 (조/억) — 거래대금처럼 큰 숫자에 사용.
// 1_073_000_000_000_000 → "1,073조원" / 304_436_200_000 → "3,044억원"
export function formatKoreanMoney(value: number): string {
  const amount = Math.round(value);
  const absAmount = Math.abs(amount);
  const sign = amount < 0 ? "-" : "";
  const nf = new Intl.NumberFormat("ko-KR");
  const JO = 1_000_000_000_000;
  const EOK = 100_000_000;
  if (absAmount >= JO) {
    const jo = Math.floor(absAmount / JO);
    const eok = Math.floor((absAmount % JO) / EOK);
    return eok > 0
      ? `${sign}${nf.format(jo)}조 ${nf.format(eok)}억원`
      : `${sign}${nf.format(jo)}조원`;
  }
  if (absAmount >= EOK) {
    const eok = Math.floor(absAmount / EOK);
    return `${sign}${nf.format(eok)}억원`;
  }
  return `${sign}${nf.format(absAmount)}원`;
}

export function formatPrice(value: number): string {
  return new Intl.NumberFormat("ko-KR").format(Math.round(value));
}

export function formatQty(value: number): string {
  return new Intl.NumberFormat("ko-KR").format(value) + "주";
}

// 억원 단위 입력 → "1.2조" / "3,500억". 시장 순매수 표시용.
export function formatEok(eok: number): string {
  if (Math.abs(eok) >= 10000) {
    const jo = eok / 10000;
    return `${Number.isInteger(jo) ? jo : jo.toFixed(1)}조`;
  }
  return `${eok.toLocaleString()}억`;
}

// === Percentage ===
// 입력은 소수 (rate). 0.025 → "+2.50%".
export function formatPct(rate: number, digits = 2): string {
  const pct = rate * 100;
  const sign = pct > 0 ? "+" : "";
  return `${sign}${pct.toFixed(digits)}%`;
}

// === Date / Time ===
// 백엔드는 LocalDateTime을 timezone 없이 KST 문자열로 보내고 (예: "2026-05-06T13:04:05"),
// 프론트에서 자체 계산한 시각은 UTC ISO("...Z")가 되므로 둘 다 KST로 정규화해 표시.
const KST_FORMAT_OPTIONS: Intl.DateTimeFormatOptions = {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
  timeZone: "Asia/Seoul",
};

export function formatDateTime(input: string | Date): string {
  const d = typeof input === "string" ? new Date(input) : input;
  if (Number.isNaN(d.getTime())) return "-";
  // ko-KR + Asia/Seoul → "2026. 05. 06. 13:04:05" → 정규화해 "2026-05-06 13:04:05" 형태로 변환
  const parts = new Intl.DateTimeFormat("ko-KR", KST_FORMAT_OPTIONS).formatToParts(d);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")} ${get("hour")}:${get("minute")}:${get("second")}`;
}

export function formatTime(input: string | Date): string {
  const d = typeof input === "string" ? new Date(input) : input;
  if (Number.isNaN(d.getTime())) return "-";
  return new Intl.DateTimeFormat("ko-KR", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
    timeZone: "Asia/Seoul",
  }).format(d);
}

export function formatDuration(startIso: string, endIso: string): string {
  const ms = new Date(endIso).getTime() - new Date(startIso).getTime();
  if (Number.isNaN(ms) || ms < 0) return "-";
  const totalSec = Math.floor(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return `${s}s`;
}

export function formatRelative(iso: string, now = new Date()): string {
  const ms = now.getTime() - new Date(iso).getTime();
  if (Number.isNaN(ms)) return "-";
  if (ms < 5_000) return "방금";
  const sec = Math.floor(ms / 1000);
  if (sec < 60) return `${sec}초 전`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}분 전`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}시간 전`;
  const day = Math.floor(hr / 24);
  return `${day}일 전`;
}

// === Color ===
// 한국 거래소 관행: 양수 빨강, 음수 파랑
export function colorByPnL(value: number): string {
  if (value > 0) return "text-red-600";
  if (value < 0) return "text-blue-600";
  return "text-zinc-400";
}
