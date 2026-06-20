import { useEffect, useRef, useState } from "react";

export type FlowPoint = {
  date: string; // YYYY-MM-DD
  time: string; // "08:15" 등
  value: number; // 그 구간 등락률(%, 직전 시점 대비)
  dayStart: boolean; // 그날 첫 포인트(08:15)면 true → 구분선·날짜 라벨
  label: string; // 구간 이름(오전 NXT 등)
  range: string; // "08:15 → 11:00" 등
};

const STEP = 30; // 포인트 간 가로 간격(px)
const H = 480;
const PAD_T = 20;
const PAD_B = 36;
const Y_AXIS_W = 68;
const LINE = "#34d399"; // emerald

function ticksOf(mn: number, mx: number, n = 6): number[] {
  const step = (mx - mn) / (n - 1);
  return Array.from({ length: n }, (_, i) => mn + step * i);
}

/** 누적 시장 흐름을 하나의 연속 선으로 — 가로 스크롤, y축 고정. */
export default function RegimeFlowChart({ points }: { points: FlowPoint[] }) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<number | null>(null);

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
            y={y(t) + 4}
            textAnchor="end"
            className="fill-zinc-500 text-xs"
          >
            {t >= 0 ? "+" : ""}
            {t.toFixed(1)}%
          </text>
        ))}
      </svg>

      {/* 스크롤되는 플롯 */}
      <div ref={scrollRef} className="overflow-x-auto">
        <svg
          width={width}
          height={H}
          className="block"
          onMouseLeave={() => setHover(null)}
        >
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
                  y={H - 12}
                  textAnchor="middle"
                  className="fill-zinc-400 text-xs"
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
            strokeWidth={2}
            strokeLinejoin="round"
          />
          {/* 포인트 (visible) */}
          {points.map((p, i) => (
            <circle
              key={i}
              cx={x(i)}
              cy={y(p.value)}
              r={hover === i ? 4.5 : 3}
              fill={LINE}
            />
          ))}
          {/* 넓은 hover 히트 영역 */}
          {points.map((p, i) => (
            <circle
              key={`h${i}`}
              cx={x(i)}
              cy={y(p.value)}
              r={STEP / 2 + 2}
              fill="transparent"
              onMouseEnter={() => setHover(i)}
            />
          ))}
          {/* hover 정보 패널 */}
          {hover !== null && (
            <FlowTooltip
              p={points[hover]}
              px={x(hover)}
              py={y(points[hover].value)}
              width={width}
            />
          )}
        </svg>
      </div>
    </div>
  );
}

/** 포인트 hover 시 뜨는 작은 정보 패널 (SVG 내부). */
function FlowTooltip({
  p,
  px,
  py,
  width,
}: {
  p: FlowPoint;
  px: number;
  py: number;
  width: number;
}) {
  const tw = 172;
  const th = 62;
  const tx = Math.max(2, Math.min(px - tw / 2, width - tw - 2));
  let ty = py - th - 10;
  if (ty < PAD_T) ty = py + 10;
  const date = `${p.date.slice(5).replace("-", "/")} ${p.time}`;
  return (
    <g pointerEvents="none">
      <rect x={tx} y={ty} width={tw} height={th} rx={6} fill="#18181b" stroke="#3f3f46" />
      <text x={tx + 12} y={ty + 19} fontSize={12} fill="#a1a1aa">
        {date} · {p.label}
      </text>
      <text x={tx + 12} y={ty + 35} fontSize={11} fill="#71717a">
        {p.range}
      </text>
      <text
        x={tx + 12}
        y={ty + 53}
        fontSize={14}
        fontWeight="bold"
        fill={p.value >= 0 ? "#f87171" : "#60a5fa"}
      >
        {p.value >= 0 ? "+" : ""}
        {p.value.toFixed(2)}%
      </text>
    </g>
  );
}
