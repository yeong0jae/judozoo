import { useEffect, useRef } from "react";
import {
  createChart,
  ColorType,
  type AutoscaleInfo,
  type CandlestickData,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from "lightweight-charts";
import type { MinuteCandleItem } from "../../types";

/**
 * ISO LocalDateTime(KST 벽시계) → lightweight-charts 시간(UTC 초).
 * 벽시계를 그대로 UTC로 취급해 축 라벨이 09:00.. 로 보이게 하고,
 * 키움 cntr_tm이 HTS보다 1분 이르므로 +1분 보정(표시단에서만).
 */
function toTime(iso: string): UTCTimestamp {
  const [date, time] = iso.split("T");
  const [y, mo, d] = date.split("-").map(Number);
  const [h, mi, s] = (time ?? "0:0:0").split(":").map(Number);
  return (Date.UTC(y, mo - 1, d, h, mi + 1, s || 0) / 1000) as UTCTimestamp;
}

/** 한국 관행: 상승 빨강, 하락 파랑. */
export default function CandleChart({
  candles,
  className = "w-full h-48",
}: {
  candles: MinuteCandleItem[];
  className?: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const volumeRef = useRef<ISeriesApi<"Histogram"> | null>(null);
  const fittedRef = useRef(false);
  // 가격축 휠 세로 줌 배율(1=자동맞춤, <1=확대, >1=축소)
  const priceZoomRef = useRef(1);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const chart = createChart(el, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: "#a1a1aa",
        fontSize: 11,
      },
      grid: {
        vertLines: { color: "rgba(255,255,255,0.04)" },
        horzLines: { color: "rgba(255,255,255,0.04)" },
      },
      rightPriceScale: { borderColor: "rgba(255,255,255,0.08)" },
      timeScale: {
        borderColor: "rgba(255,255,255,0.08)",
        timeVisible: true,
        secondsVisible: false,
      },
      crosshair: { mode: 0 },
    });
    // 휠 세로 줌 — 자동맞춤 범위를 중심 기준 배율만큼 넓히거나 좁힌다
    const priceAutoscale = (orig: () => AutoscaleInfo | null): AutoscaleInfo | null => {
      const base = orig();
      const f = priceZoomRef.current;
      if (!base || !base.priceRange || f === 1) return base;
      const { minValue, maxValue } = base.priceRange;
      const mid = (minValue + maxValue) / 2;
      const half = ((maxValue - minValue) / 2) * f;
      return { ...base, priceRange: { minValue: mid - half, maxValue: mid + half } };
    };
    seriesRef.current = chart.addCandlestickSeries({
      upColor: "#f43f5e",
      downColor: "#3b82f6",
      wickUpColor: "#f43f5e",
      wickDownColor: "#3b82f6",
      borderVisible: false,
      autoscaleInfoProvider: priceAutoscale,
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

    // 가격축(오른쪽) 위에서 휠 → 세로 줌. 차트 영역 위 휠은 기본(가로 줌) 유지.
    const onWheel = (e: WheelEvent) => {
      const axisW = chart.priceScale("right").width();
      const x = e.clientX - el.getBoundingClientRect().left;
      if (x < el.clientWidth - axisW) return; // 차트 영역 → 라이브러리 기본 처리
      e.preventDefault();
      e.stopImmediatePropagation();
      const step = e.deltaY > 0 ? 1.04 : 1 / 1.04; // 위로=확대, 아래로=축소
      priceZoomRef.current = Math.min(6, Math.max(0.15, priceZoomRef.current * step));
      seriesRef.current?.applyOptions({ autoscaleInfoProvider: priceAutoscale });
    };
    el.addEventListener("wheel", onWheel, { capture: true, passive: false });

    return () => {
      el.removeEventListener("wheel", onWheel, { capture: true });
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
      volumeRef.current = null;
    };
  }, []);

  useEffect(() => {
    const series = seriesRef.current;
    if (!series) return;
    const data: CandlestickData[] = candles.map((c) => ({
      time: toTime(c.time),
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    }));
    series.setData(data);
    volumeRef.current?.setData(
      candles.map((c) => ({
        time: toTime(c.time),
        value: c.tradingValue, // 거래대금(원)
        // 상승 빨강, 하락 파랑 (반투명)
        color: c.close >= c.open ? "rgba(244,63,94,0.5)" : "rgba(59,130,246,0.5)",
      })),
    );
    if (!fittedRef.current && data.length > 0) {
      chartRef.current?.timeScale().fitContent();
      fittedRef.current = true;
    }
  }, [candles]);

  return <div ref={containerRef} className={className} />;
}
