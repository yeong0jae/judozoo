// 토스 지수 캔들(MarketCandleItem) → CandleChart 시리즈 변환.
// 실제 OHLCV라 지수도 캔들로 그린다(키움 지수값 10초 합성과 달리 정직한 시가·고가·저가).
import type { CandlestickData, HistogramData, UTCTimestamp } from "lightweight-charts";
import type { CandleSeries } from "../common/CandleChart";
import type { MarketCandleItem } from "../../types";

const UP = "rgba(244,63,94,0.5)"; // 상승 빨강
const DOWN = "rgba(59,130,246,0.5)"; // 하락 파랑

/** 일봉 — time은 영업일(yyyy-MM-dd), 오름차순. */
export function marketDailySeries(items: MarketCandleItem[]): CandleSeries {
  const sorted = [...items].sort((a, b) => a.date.localeCompare(b.date));
  const candles: CandlestickData[] = [];
  const volumes: HistogramData[] = [];
  for (const c of sorted) {
    candles.push({ time: c.date, open: c.open, high: c.high, low: c.low, close: c.close });
    volumes.push({ time: c.date, value: c.volume, color: c.close >= c.open ? UP : DOWN });
  }
  return { candles, volumes };
}

/** 분봉 — KST 벽시계를 UTC로 취급해 축 라벨이 09:00.. 로 보이게 한다(토스 데이터라 키움 +1분 보정 불필요). */
export function marketMinuteSeries(items: MinuteLike[]): CandleSeries {
  const sorted = [...items].sort((a, b) => (a.date + a.time).localeCompare(b.date + b.time));
  const candles: CandlestickData[] = [];
  const volumes: HistogramData[] = [];
  for (const c of sorted) {
    const [y, mo, d] = c.date.split("-").map(Number);
    const [h, mi, s] = c.time.split(":").map(Number);
    const t = (Date.UTC(y, mo - 1, d, h, mi, s || 0) / 1000) as UTCTimestamp;
    candles.push({ time: t, open: c.open, high: c.high, low: c.low, close: c.close });
    volumes.push({ time: t, value: c.volume, color: c.close >= c.open ? UP : DOWN });
  }
  return { candles, volumes };
}

type MinuteLike = Pick<MarketCandleItem, "date" | "time" | "open" | "high" | "low" | "close" | "volume">;
