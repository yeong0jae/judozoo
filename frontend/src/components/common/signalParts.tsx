import type { SignalEventItem, SignalEventType } from "../../types";
import { formatKoreanMoney, formatPrice } from "../../lib/format";

/** 종목 시그널 표시 규칙 — 시그널 화면과 홈의 「최근 시그널」이 같이 쓴다. */

/** ISO LocalDateTime → HH:mm:ss (타임존 변환 없이 문자열에서 직접). */
export function clockOf(iso: string): string {
  return iso.slice(11, 19);
}

/** 정규장(09:00~15:20) 시각은 흰색으로 강조, 장외(프리/애프터)는 회색. */
export function clockClass(iso: string): string {
  const hm = iso.slice(11, 16); // HH:mm
  return hm >= "09:00" && hm <= "15:20" ? "text-zinc-100" : "text-zinc-500";
}

export const EVENT_META: Record<SignalEventType, { label: string; chip: string; dot: string }> = {
  VOLUME_SPIKE: { label: "스파이크", chip: "bg-rose-500/15 text-rose-300", dot: "bg-rose-400" },
  // 지지·저항 화면과 같은 색을 쓴다 — 주황=위(저항 방향), 하늘=아래(지지 방향).
  // 색이 화면마다 다른 방향을 뜻하면 읽는 사람이 매번 다시 배워야 한다.
  MA_REBOUND: { label: "반등", chip: "bg-orange-500/15 text-orange-400", dot: "bg-orange-400" },
  MA_BREAKDOWN: { label: "꺾임", chip: "bg-sky-500/15 text-sky-400", dot: "bg-sky-400" },
  // 생성 중단(2026-09-13). 과거 날짜 조회용으로만 남는다 — 흐린 톤으로 구분한다.
  BREAKOUT: { label: "돌파", chip: "bg-zinc-700/40 text-zinc-400", dot: "bg-zinc-500" },
  BREAKOUT_IMMINENT: { label: "임박", chip: "bg-zinc-700/40 text-zinc-500", dot: "bg-zinc-600" },
};

/**
 * 이벤트별 핵심 수치 한 줄. 돌파선 가격은 그때의 현재가×(1+갭/100)으로 역산.
 * 스파이크는 배율+그 분봉 거래대금(rose)과 그 순간 누적 거래대금(흐리게)을 함께 보인다.
 */
export function detailOf(e: SignalEventItem) {
  if (e.eventType === "VOLUME_SPIKE") {
    if (!e.spikeRatio) return "";
    const dirCls =
      e.spikeDirection === "BUY" ? "text-red-400" : e.spikeDirection === "SELL" ? "text-blue-400" : "text-zinc-500";
    const dirLabel =
      e.spikeDirection === "BUY" ? "매수" : e.spikeDirection === "SELL" ? "매도" : e.spikeDirection === "FLAT" ? "보합" : "";
    return (
      <>
        <span className="text-rose-300">
          {e.spikeRatio.toFixed(1)}배
          {e.minuteTradingValue != null && ` ${formatKoreanMoney(e.minuteTradingValue)}`}
        </span>
        {dirLabel && <span className={dirCls}> {dirLabel}</span>}
        <span className="text-zinc-500"> · 누적 {formatKoreanMoney(e.tradingValue)}</span>
      </>
    );
  }
  if (e.eventType === "MA_REBOUND" || e.eventType === "MA_BREAKDOWN") {
    // 칩·점과 같은 색을 쓴다 — 주황=반등(위), 하늘=꺾임(아래)
    const up = e.eventType === "MA_REBOUND";
    return (
      <span className={up ? "text-orange-400" : "text-sky-300"}>
        5분 20이평{e.ma != null && ` ${formatPrice(e.ma)}원`}{" "}
        {up ? "상향돌파" : "하향이탈"}
      </span>
    );
  }
  if (e.gapRate == null) return e.eventType === "BREAKOUT" ? "전고 돌파" : "";
  const line = Math.round(e.currentPrice * (1 + e.gapRate / 100));
  if (e.eventType === "BREAKOUT") return `${formatPrice(line)}원 돌파`;
  return `${formatPrice(line)}원 돌파까지 ${formatPrice(line - e.currentPrice)}원 (${e.gapRate.toFixed(2)}%) 남음`;
}
