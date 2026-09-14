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
  type LineData,
  type UTCTimestamp,
} from "lightweight-charts";
import type { DailyCandleItem, MinuteCandleItem } from "../../types";

const UP = "rgba(244,63,94,0.5)"; // 상승 빨강
const DOWN = "rgba(59,130,246,0.5)"; // 하락 파랑
// 이평선 보라 — 캔들(빨강·파랑)과도, 저항선 주황·지지선 하늘색과도 겹치지 않는 색.
const MA = "#a78bfa";

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

/**
 * 종가 단순이평. 창이 다 차기 전 구간은 그리지 않는다.
 * 정의(종가 합 ÷ 기간)는 백엔드 반등·꺾임 판정과 같아, 선을 넘는 순간이 시그널이 뜨는 순간이다.
 */
function movingAverage(candles: CandlestickData[], period: number): LineData[] {
  const out: LineData[] = [];
  let sum = 0;
  for (let i = 0; i < candles.length; i++) {
    sum += candles[i].close;
    if (i >= period) sum -= candles[i - period].close;
    if (i >= period - 1) out.push({ time: candles[i].time, value: sum / period });
  }
  return out;
}

/** 한국 관행: 상승 빨강, 하락 파랑. 가격축 정수+쉼표, 하단 거래량 막대. */
export default function CandleChart({
  series,
  timeVisible = true,
  priceLines,
  priceDecimals = 0,
  maPeriod,
  className = "w-full h-48",
}: {
  series: CandleSeries;
  timeVisible?: boolean;
  /** 가로 기준선들 — 저항선(고가)·지지선(저가). 값이 바뀌면 통째로 교체한다. */
  priceLines?: { price: number; title: string; color: string }[];
  priceDecimals?: number; // 가격축 소수 자릿수 (국내 원=0, 해외 달러=2)
  /** 주면 그 기간의 종가 이평선을 겹쳐 그린다. 분봉에만 준다 — 일봉엔 의미가 다르다. */
  maPeriod?: number;
  className?: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const volumeRef = useRef<ISeriesApi<"Histogram"> | null>(null);
  const maRef = useRef<ISeriesApi<"Line"> | null>(null);
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
      upColor: "#f43f5e",
      downColor: "#3b82f6",
      wickUpColor: "#f43f5e",
      wickDownColor: "#3b82f6",
      borderVisible: false,
      // 가격축: priceDecimals 자리(원=0, 달러=2) + 천 단위 쉼표
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
    // 캔들은 위 75%, 거래량은 아래 20%에 별도 오버레이 스케일로
    chart.priceScale("right").applyOptions({ scaleMargins: { top: 0.05, bottom: 0.25 } });
    const volume = chart.addHistogramSeries({
      priceFormat: { type: "volume" },
      priceScaleId: "",
    });
    volume.priceScale().applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } });
    volumeRef.current = volume;
    // 이평선은 캔들과 같은 가격축을 쓴다. 축 라벨·기준선은 끈다 — 저항·지지선이 이미 축을 쓴다.
    maRef.current = maPeriod
      ? chart.addLineSeries({
          color: MA,
          lineWidth: 1,
          priceLineVisible: false,
          lastValueVisible: false,
          crosshairMarkerVisible: false,
        })
      : null;
    chartRef.current = chart;

    return () => {
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
      volumeRef.current = null;
      maRef.current = null;
    };
  }, [timeVisible, priceDecimals, maPeriod]);

  useEffect(() => {
    const s = seriesRef.current;
    if (!s) return;
    s.setData(series.candles);
    volumeRef.current?.setData(series.volumes);
    if (maRef.current && maPeriod) maRef.current.setData(movingAverage(series.candles, maPeriod));
    // 가로 기준선 — 값 바뀌면 통째로 교체
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
  }, [series, priceLines, maPeriod]);

  return <div ref={containerRef} className={className} />;
}
