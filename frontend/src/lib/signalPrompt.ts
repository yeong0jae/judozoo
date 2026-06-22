import type { SignalEventItem, SignalEventType } from "../types";
import { formatKoreanMoney, formatPrice } from "./format";

const TYPE_LABEL: Record<SignalEventType, string> = {
  BREAKOUT: "돌파",
  BREAKOUT_IMMINENT: "임박",
  VOLUME_SPIKE: "스파이크",
};

const NO_THEME = "테마없음";

function clockOf(iso: string): string {
  return iso.slice(11, 19);
}

function shortCode(code: string): string {
  const i = code.indexOf("_");
  return i > 0 ? code.slice(0, i) : code;
}

function pct(v: number): string {
  return `${v > 0 ? "+" : ""}${v.toFixed(2)}%`;
}

/** 이벤트별 비고 — 화면 detailOf와 같은 의미(스파이크 배율·분봉, 돌파선·남은 갭). */
function noteOf(e: SignalEventItem): string {
  if (e.eventType === "VOLUME_SPIKE") {
    const r = e.spikeRatio ? `배율 ${e.spikeRatio.toFixed(1)}배` : "";
    const d =
      e.spikeDirection === "BUY" ? " 매수" : e.spikeDirection === "SELL" ? " 매도" : e.spikeDirection === "FLAT" ? " 보합" : "";
    const m = e.minuteTradingValue != null ? ` / 분봉 ${formatKoreanMoney(e.minuteTradingValue)}` : "";
    return r + d + m;
  }
  if (e.gapRate == null) return e.eventType === "BREAKOUT" ? "전고 돌파" : "";
  const line = Math.round(e.currentPrice * (1 + e.gapRate / 100));
  if (e.eventType === "BREAKOUT") return `돌파선 ${formatPrice(line)}`;
  return `돌파선 ${formatPrice(line)}까지 ${e.gapRate.toFixed(2)}% 남음`;
}

/** 같은 유형이 연속되면 "13:16 임박 ×6"처럼 접어 시퀀스를 읽기 쉽게. */
function sequenceOf(events: SignalEventItem[]): string {
  const parts: { time: string; type: string; count: number }[] = [];
  for (const e of events) {
    const type = TYPE_LABEL[e.eventType];
    const prev = parts[parts.length - 1];
    if (prev && prev.type === type) {
      prev.count += 1;
    } else {
      parts.push({ time: clockOf(e.occurredAt).slice(0, 5), type, count: 1 });
    }
  }
  return parts.map((p) => (p.count > 1 ? `${p.time} ${p.type} ×${p.count}` : `${p.time} ${p.type}`)).join(" → ");
}

/**
 * 시그널 로그를 LLM에 붙여넣을 텍스트로 정제한다.
 * 프롬프트 + 사전 집계(개요·테마별·시간대별·종목별) + 원본 이벤트(시간 오름차순).
 * 숫자는 모두 코드가 계산해 넣어, 붙여넣은 쪽 LLM은 해석만 하면 된다.
 */
export function buildSignalPrompt(date: string, events: SignalEventItem[]): string {
  const asc = [...events].sort((a, b) => a.occurredAt.localeCompare(b.occurredAt));

  const byType: Record<SignalEventType, number> = {
    BREAKOUT: 0,
    BREAKOUT_IMMINENT: 0,
    VOLUME_SPIKE: 0,
  };
  asc.forEach((e) => (byType[e.eventType] += 1));
  const stockCount = new Set(asc.map((e) => e.stockCode)).size;

  // 테마별 집계
  const themeMap = new Map<string, { count: number; stocks: Set<string>; t: Record<SignalEventType, number> }>();
  for (const e of asc) {
    const key = e.theme ?? NO_THEME;
    const cur = themeMap.get(key) ?? { count: 0, stocks: new Set(), t: { BREAKOUT: 0, BREAKOUT_IMMINENT: 0, VOLUME_SPIKE: 0 } };
    cur.count += 1;
    cur.stocks.add(e.stockCode);
    cur.t[e.eventType] += 1;
    themeMap.set(key, cur);
  }
  const themeRows = [...themeMap.entries()]
    .sort((a, b) => b[1].count - a[1].count)
    .map(([theme, v]) => `${theme} | ${v.count} | ${v.stocks.size} | ${v.t.BREAKOUT}/${v.t.BREAKOUT_IMMINENT}/${v.t.VOLUME_SPIKE}`);

  // 시간대별 집계 (시 단위)
  const hourMap = new Map<string, Record<SignalEventType, number>>();
  for (const e of asc) {
    const hh = e.occurredAt.slice(11, 13);
    const cur = hourMap.get(hh) ?? { BREAKOUT: 0, BREAKOUT_IMMINENT: 0, VOLUME_SPIKE: 0 };
    cur[e.eventType] += 1;
    hourMap.set(hh, cur);
  }
  const hourRows = [...hourMap.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([hh, v]) => `${hh}시 | ${v.BREAKOUT}/${v.BREAKOUT_IMMINENT}/${v.VOLUME_SPIKE}`);

  // 종목별 요약
  const stockMap = new Map<string, SignalEventItem[]>();
  for (const e of asc) {
    const arr = stockMap.get(e.stockCode) ?? [];
    arr.push(e);
    stockMap.set(e.stockCode, arr);
  }
  const stockRows = [...stockMap.values()]
    .sort((a, b) => b.length - a.length)
    .map((arr) => {
      const last = arr[arr.length - 1];
      const maxRatio = arr.reduce((m, e) => Math.max(m, e.spikeRatio ?? 0), 0);
      const ratioStr = maxRatio > 0 ? `${maxRatio.toFixed(1)}배` : "-";
      return `${last.stockName}(${shortCode(last.stockCode)}) | ${last.theme ?? NO_THEME} | ${ratioStr} | ${pct(last.priceChangeRate)} | ${sequenceOf(arr)}`;
    });

  // 원본 이벤트
  const eventRows = asc.map(
    (e) =>
      `${clockOf(e.occurredAt)} · ${TYPE_LABEL[e.eventType]} · ${e.stockName}(${shortCode(e.stockCode)}) · ${e.theme ?? NO_THEME} · ${formatPrice(e.currentPrice)} · ${pct(e.priceChangeRate)} · ${formatKoreanMoney(e.tradingValue)} · ${noteOf(e)}`,
  );

  return [
    `다음은 ${date} 주도주 시그널 로그야. 장중 발생한 돌파/임박/스파이크 전이를 시간순으로 기록한 거야.`,
    `이걸 근거로 오늘 시장 흐름을 분석하고 매매를 복기해줘:`,
    `1) 주도 테마와 테마 순환`,
    `2) 시간대별 수급 흐름`,
    `3) 주목 종목(임박→돌파→스파이크로 이어진 종목 등)`,
    `4) 전반적 시장 톤`,
    `5) 이상적 매매 복기 — 어느 종목·어느 신호에서 진입했어야 했고 언제 정리했어야 했는지, 믿을 만한 신호와 무시했어야 할 신호(돌파 실패·임박 무산·고점 스파이크 등)는 무엇이었는지`,
    `주의: 각 종목 가격은 '시그널 발생 시점' 값만 있고 그 사이 고저는 없어. 주어진 시점 가격들 안에서만 복기하고, 없는 값은 추정하지 마.`,
    ``,
    `## 개요`,
    `- 총 이벤트: ${asc.length}건 (돌파 ${byType.BREAKOUT} · 임박 ${byType.BREAKOUT_IMMINENT} · 스파이크 ${byType.VOLUME_SPIKE})`,
    `- 등장 종목: ${stockCount}개`,
    ``,
    `## 테마별 (이벤트 많은 순)`,
    `테마 | 이벤트 | 종목 | 돌파/임박/스파이크`,
    ...themeRows,
    ``,
    `## 시간대별`,
    `시 | 돌파/임박/스파이크`,
    ...hourRows,
    ``,
    `## 종목별 요약 (이벤트 많은 순)`,
    `종목(코드) | 테마 | 최고배율 | 최종등락률 | 전이 시퀀스`,
    ...stockRows,
    ``,
    `## 원본 이벤트 (시간순)`,
    `시각 · 유형 · 종목(코드) · 테마 · 현재가 · 등락률 · 누적거래대금 · 비고`,
    ...eventRows,
  ].join("\n");
}
