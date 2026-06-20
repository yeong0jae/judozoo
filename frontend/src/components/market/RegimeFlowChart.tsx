import { useEffect, useRef } from "react";

export type FlowPoint = {
  date: string; // YYYY-MM-DD
  time: string; // "08:15" 등
  value: number; // 그 구간 등락률(%, 직전 시점 대비)
  dayStart: boolean; // 그날 첫 포인트(08:15)면 true → 구분선·날짜 라벨
};

const STEP = 18; // 포인트 간 가로 간격(px)
const H = 340;
const PAD_T = 16;
const PAD_B = 28;
const Y_AXIS_W = 56;
const LINE = "#34d399"; // emerald

function ticksOf(mn: number, mx: number, n = 6): number[] {
  const step = (mx - mn) / (n - 1);
  return Array.from({ length: n }, (_, i) => mn + step * i);
}

/** 누적 시장 흐름을 하나의 연속 선으로 — 가로 스크롤, y축 고정. */
export default function RegimeFlowChart({ points }: { points: FlowPoint[] }) {
  const scrollRef = useRef<HTMLDivElement>(null);

  // 진입 시 최신(오른쪽 끝)으로 스크롤
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollLeft = el.scrollWidth;
  }, [points.length]);

  if (points.length === 0) return null;

  const vals = points.map((p) => p.value).concat(0);
  let mn = Math.min(...vals);
  let mx = Math.max(...vals);
  const sp = mx - mn || 1;
  mn -= sp * 0.1;
  mx += sp * 0.1;

  const plotH = H - PAD_T - PAD_B;
  const y = (v: number) => PAD_T + ((mx - v) / (mx - mn)) * plotH;
  const x = (i: number) => i * STEP + STEP / 2;
  const width = points.length * STEP + STEP;

  const ticks = ticksOf(mn, mx);
  const polyline = points.map((p, i) => `${x(i)},${y(p.value)}`).join(" ");

  return (
    <div className="flex">
      {/* 고정 y축 */}
      <svg width={Y_AXIS_W} height={H} className="shrink-0">
        {ticks.map((t, i) => (
          <text
            key={i}
            x={Y_AXIS_W - 8}
            y={y(t) + 3}
            textAnchor="end"
            className="fill-zinc-600 text-[10px]"
          >
            {t >= 0 ? "+" : ""}
            {t.toFixed(1)}%
          </text>
        ))}
      </svg>

      {/* 스크롤되는 플롯 */}
      <div ref={scrollRef} className="overflow-x-auto">
        <svg width={width} height={H} className="block">
          {/* 가로 그리드 */}
          {ticks.map((t, i) => (
            <line
              key={i}
              x1={0}
              x2={width}
              y1={y(t)}
              y2={y(t)}
              className={t === 0 ? "stroke-zinc-700" : "stroke-zinc-800/60"}
              strokeDasharray={t === 0 ? "" : "2 3"}
            />
          ))}
          {/* 날짜 구분선 + 라벨 */}
          {points.map((p, i) =>
            p.dayStart ? (
              <g key={`d${i}`}>
                <line
                  x1={x(i)}
                  x2={x(i)}
                  y1={PAD_T}
                  y2={H - PAD_B}
                  className="stroke-zinc-800"
                />
                <text
                  x={x(i)}
                  y={H - 10}
                  textAnchor="middle"
                  className="fill-zinc-500 text-[10px]"
                >
                  {p.date.slice(5).replace("-", "/")}
                </text>
              </g>
            ) : null,
          )}
          {/* 흐름 선 */}
          <polyline
            points={polyline}
            fill="none"
            stroke={LINE}
            strokeWidth={1.5}
            strokeLinejoin="round"
          />
          {/* 포인트 + 네이티브 툴팁 */}
          {points.map((p, i) => (
            <circle key={i} cx={x(i)} cy={y(p.value)} r={2.5} fill={LINE}>
              <title>
                {p.date.slice(5).replace("-", "/")} {p.time} ·{" "}
                {p.value >= 0 ? "+" : ""}
                {p.value.toFixed(2)}%
              </title>
            </circle>
          ))}
        </svg>
      </div>
    </div>
  );
}
