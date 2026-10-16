import { useEffect, useRef } from "react";
import {
  createChart,
  ColorType,
  LineStyle,
  type CandlestickData,
  type HistogramData,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type UTCTimestamp,
} from "lightweight-charts";
import type { DailyCandleItem, MinuteCandleItem } from "../../types";

const UP = "rgba(240,68,82,0.5)";
const DOWN = "rgba(59,130,246,0.5)";

export interface CandleSeries {
  candles: CandlestickData[];
  volumes: HistogramData[];
}

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

export default function CandleChart({
  series,
  timeVisible = true,
  priceLines,
  priceDecimals = 0,
  className = "w-full h-48",
}: {
  series: CandleSeries;
  timeVisible?: boolean;
  priceLines?: { price: number; title: string; color: string }[];
  priceDecimals?: number;
  className?: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const volumeRef = useRef<ISeriesApi<"Histogram"> | null>(null);
  const priceLineRefs = useRef<IPriceLine[]>([]);
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
      upColor: "#f04452",
      downColor: "#3b82f6",
      wickUpColor: "#f04452",
      wickDownColor: "#3b82f6",
      borderVisible: false,
      priceFormat: {
        type: "custom",
        minMove: priceDecimals > 0 ? 1 / 10 ** priceDecimals : 1,
        formatter: (p: number) =>
          p.toLocaleString("en-US", {
            minimumFractionDigits: priceDecimals,
            maximumFractionDigits: priceDecimals,
          }),
      },
    });
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
  }, [timeVisible, priceDecimals]);

  useEffect(() => {
    const s = seriesRef.current;
    if (!s) return;
    s.setData(series.candles);
    volumeRef.current?.setData(series.volumes);
    for (const line of priceLineRefs.current) s.removePriceLine(line);
    priceLineRefs.current = (priceLines ?? []).map((l) =>
      s.createPriceLine({
        price: l.price,
        color: l.color,
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: l.title,
      }),
    );
    if (!fittedRef.current && series.candles.length > 0) {
      chartRef.current?.timeScale().fitContent();
      fittedRef.current = true;
    }
  }, [series, priceLines]);

  return <div ref={containerRef} className={className} />;
}
