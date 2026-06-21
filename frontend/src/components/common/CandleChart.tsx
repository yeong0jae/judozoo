import { useEffect, useRef } from "react";
import {
  createChart,
  ColorType,
  type CandlestickData,
  type HistogramData,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from "lightweight-charts";
import type { DailyCandleItem, MinuteCandleItem } from "../../types";

const UP = "rgba(244,63,94,0.5)"; // 상승 빨강
const DOWN = "rgba(59,130,246,0.5)"; // 하락 파랑

export interface CandleSeries {
  candles: CandlestickData[];
  volumes: HistogramData[];
}

/**
 * 분봉 → 시리즈. ISO LocalDateTime(KST 벽시계)을 UTC로 취급해 축 라벨이 09:00.. 로 보이게 하고,
 * 키움 cntr_tm이 HTS보다 1분 이르므로 +1분 보정. 거래량 막대는 거래대금(원).
 */
export function minuteSeries(items: MinuteCandleItem[]): CandleSeries {
  const candles: CandlestickData[] = [];
  const volumes: HistogramData[] = [];
  for (const c of items) {
    const [date, time] = c.time.split("T");
    const [y, mo, d] = date.split("-").map(Number);
    const [h, mi, s] = (time ?? "0:0:0").split(":").map(Number);
    const t = (Date.UTC(y, mo - 1, d, h, mi + 1, s || 0) / 1000) as UTCTimestamp;
    candles.push({ time: t, open: c.open, high: c.high, low: c.low, close: c.close });
    volumes.push({ time: t, value: c.tradingValue, color: c.close >= c.open ? UP : DOWN });
  }
  return { candles, volumes };
}

/** 일봉 → 시리즈. time은 영업일(yyyy-MM-dd), 오름차순. 거래량 막대 ≈ 종가×거래량(거래대금). */
export function dailySeries(items: DailyCandleItem[]): CandleSeries {
  const sorted = [...items].sort((a, b) => a.date.localeCompare(b.date));
  const candles: CandlestickData[] = [];
  const volumes: HistogramData[] = [];
  for (const c of sorted) {
    candles.push({ time: c.date, open: c.open, high: c.high, low: c.low, close: c.close });
    volumes.push({ time: c.date, value: c.close * c.volume, color: c.close >= c.open ? UP : DOWN });
  }
  return { candles, volumes };
}

/** 한국 관행: 상승 빨강, 하락 파랑. 가격축 정수+쉼표, 하단 거래량 막대. */
export default function CandleChart({
  series,
  timeVisible = true,
  className = "w-full h-48",
}: {
  series: CandleSeries;
  timeVisible?: boolean;
  className?: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const volumeRef = useRef<ISeriesApi<"Histogram"> | null>(null);
  const fittedRef = useRef(false);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const chart = createChart(el, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: "#a1a1aa",
        fontSize: 13,
      },
      grid: {
        vertLines: { color: "rgba(255,255,255,0.04)" },
        horzLines: { color: "rgba(255,255,255,0.04)" },
      },
      rightPriceScale: { borderColor: "rgba(255,255,255,0.08)" },
      timeScale: {
        borderColor: "rgba(255,255,255,0.08)",
        timeVisible,
        secondsVisible: false,
      },
      crosshair: { mode: 0 },
    });
    seriesRef.current = chart.addCandlestickSeries({
      upColor: "#f43f5e",
      downColor: "#3b82f6",
      wickUpColor: "#f43f5e",
      wickDownColor: "#3b82f6",
      borderVisible: false,
      // 가격축: 정수(원) + 천 단위 쉼표
      priceFormat: {
        type: "custom",
        minMove: 1,
        formatter: (p: number) => Math.round(p).toLocaleString("en-US"),
      },
    });
    // 캔들은 위 75%, 거래량은 아래 20%에 별도 오버레이 스케일로
    chart.priceScale("right").applyOptions({ scaleMargins: { top: 0.05, bottom: 0.25 } });
    const volume = chart.addHistogramSeries({
      priceFormat: { type: "volume" },
      priceScaleId: "",
    });
    volume.priceScale().applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } });
    volumeRef.current = volume;
    chartRef.current = chart;

    return () => {
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
      volumeRef.current = null;
    };
  }, [timeVisible]);

  useEffect(() => {
    const s = seriesRef.current;
    if (!s) return;
    s.setData(series.candles);
    volumeRef.current?.setData(series.volumes);
    if (!fittedRef.current && series.candles.length > 0) {
      chartRef.current?.timeScale().fitContent();
      fittedRef.current = true;
    }
  }, [series]);

  return <div ref={containerRef} className={className} />;
}
