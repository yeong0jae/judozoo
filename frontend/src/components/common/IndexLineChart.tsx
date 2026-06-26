import { useEffect, useRef } from "react";
import {
  createChart,
  ColorType,
  type IChartApi,
  type ISeriesApi,
  type LineData,
  type UTCTimestamp,
} from "lightweight-charts";
import type { IndexMinuteCandleItem } from "../../types";

const UP = "#f43f5e"; // 상승 빨강
const DOWN = "#3b82f6"; // 하락 파랑

/**
 * 지수 선차트 — 분봉 종가를 이어 그린다. (지수값은 10초 샘플 합성이라 캔들보다 선이 정직)
 * 당일 시초가 대비 오르면 빨강·내리면 파랑 한 가지 색. 거래량 막대 없음, 가격축 소수 2자리.
 */
export default function IndexLineChart({
  items,
  className = "w-full h-[28rem]",
}: {
  items: IndexMinuteCandleItem[];
  className?: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Line"> | null>(null);
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
      timeScale: { borderColor: "rgba(255,255,255,0.08)", timeVisible: true, secondsVisible: false },
      crosshair: { mode: 0 },
    });
    seriesRef.current = chart.addLineSeries({
      color: UP,
      lineWidth: 2,
      priceFormat: {
        type: "custom",
        minMove: 0.01,
        formatter: (p: number) =>
          p.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
      },
    });
    chart.priceScale("right").applyOptions({ scaleMargins: { top: 0.1, bottom: 0.1 } });
    chartRef.current = chart;

    return () => {
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, []);

  useEffect(() => {
    const s = seriesRef.current;
    if (!s) return;
    const data: LineData[] = items.map((c) => {
      const [date, time] = c.time.split("T");
      const [y, mo, d] = date.split("-").map(Number);
      const [h, mi, sec] = (time ?? "0:0:0").split(":").map(Number);
      const t = (Date.UTC(y, mo - 1, d, h, mi, sec || 0) / 1000) as UTCTimestamp;
      return { time: t, value: c.close };
    });
    s.setData(data);
    // 당일 시초가 대비 현재 종가가 오르면 빨강, 내리면 파랑 (선 전체 한 색)
    if (items.length > 0) {
      const up = items[items.length - 1].close >= items[0].open;
      s.applyOptions({ color: up ? UP : DOWN });
    }
    if (!fittedRef.current && data.length > 0) {
      chartRef.current?.timeScale().fitContent();
      fittedRef.current = true;
    }
  }, [items]);

  return <div ref={containerRef} className={className} />;
}
