type ChartPoint = { t: number; gap2: number };

function fmtTime(ms: number): string {
  return new Date(ms).toLocaleTimeString("ko-KR", {
    timeZone: "Asia/Seoul",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

function fmtPct(v: number): string {
  return `${v >= 0 ? "+" : ""}${v.toFixed(2)}%`;
}

/**
 * 당일 Gap2(아침 NXT 대비 본장) 추이 선그래프. 라이브러리 없이 SVG로 그린다.
 * 0 기준선과 Gap1(아침 NXT 갭) 기준선을 함께 표시한다.
 */
export default function RegimeChart({
  points,
  gap1,
}: {
  points: ChartPoint[];
  gap1: number | null;
}) {
  if (points.length < 2) {
    return (
      <div className="flex h-[200px] items-center justify-center text-sm text-zinc-600">
        본장 데이터 누적 중…
      </div>
    );
  }

  const W = 640;
  const H = 200;
  const padL = 44;
  const padR = 14;
  const padT = 14;
  const padB = 26;

  const ts = points.map((p) => p.t);
  const tMin = Math.min(...ts);
  const tMax = Math.max(...ts);

  const yVals = [...points.map((p) => p.gap2), 0, ...(gap1 != null ? [gap1] : [])];
  let yMin = Math.min(...yVals);
  let yMax = Math.max(...yVals);
  const span = yMax - yMin || 1;
  yMin -= span * 0.12;
  yMax += span * 0.12;

  const x = (t: number) =>
    padL + ((t - tMin) / Math.max(1, tMax - tMin)) * (W - padL - padR);
  const y = (v: number) =>
    padT + (1 - (v - yMin) / (yMax - yMin)) * (H - padT - padB);

  const path = points
    .map((p, i) => `${i === 0 ? "M" : "L"}${x(p.t).toFixed(1)},${y(p.gap2).toFixed(1)}`)
    .join(" ");

  const lastY = points[points.length - 1].gap2;
  const lineColor = lastY >= 0 ? "#34d399" : "#fb7185"; // emerald / rose
  const y0 = y(0);

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img">
      {/* 0 기준선 */}
      <line x1={padL} x2={W - padR} y1={y0} y2={y0} stroke="#3f3f46" strokeWidth={1} />
      <text x={padL - 6} y={y0 + 3} textAnchor="end" fontSize={10} fill="#71717a">
        0%
      </text>

      {/* Gap1 기준선 (아침 NXT 갭) */}
      {gap1 != null && (
        <>
          <line
            x1={padL}
            x2={W - padR}
            y1={y(gap1)}
            y2={y(gap1)}
            stroke="#52525b"
            strokeWidth={1}
            strokeDasharray="4 3"
          />
          <text x={padL - 6} y={y(gap1) + 3} textAnchor="end" fontSize={10} fill="#71717a">
            {fmtPct(gap1)}
          </text>
        </>
      )}

      {/* Gap2 추이 */}
      <path d={path} fill="none" stroke={lineColor} strokeWidth={2} />
      <circle
        cx={x(points[points.length - 1].t)}
        cy={y(lastY)}
        r={3}
        fill={lineColor}
      />

      {/* x축 시간 */}
      <text x={padL} y={H - 8} fontSize={10} fill="#71717a">
        {fmtTime(tMin)}
      </text>
      <text x={W - padR} y={H - 8} textAnchor="end" fontSize={10} fill="#71717a">
        {fmtTime(tMax)}
      </text>
    </svg>
  );
}
