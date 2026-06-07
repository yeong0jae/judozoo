import { useState } from "react";
import type { RegimeDaily } from "../../types";

const SEGMENTS = [
  { key: "오전 NXT", color: "#38bdf8", from: "전일종가", to: "08:15" },
  { key: "오전장", color: "#a78bfa", from: "08:15", to: "10:00" },
  { key: "오후 마감", color: "#fbbf24", from: "10:00", to: "15:30" },
] as const;

function fmtDate(iso: string): string {
  const d = new Date(iso);
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

function fmtPct(v: number, digits = 2): string {
  return `${v >= 0 ? "+" : ""}${v.toFixed(digits)}%`;
}

/** 같은 기준(아침 NXT) 대비 두 값에서 from→to 변동(%) */
function relative(from: number, to: number): number {
  return ((1 + to / 100) / (1 + from / 100) - 1) * 100;
}

function niceTicks(mn: number, mx: number): number[] {
  const range = mx - mn;
  const step = range <= 3 ? 0.5 : range <= 6 ? 1 : range <= 15 ? 2 : 5;
  const ticks: number[] = [];
  for (let t = Math.ceil(mn / step) * step; t <= mx + 1e-9; t += step) {
    ticks.push(Math.round(t * 100) / 100);
  }
  if (mn < 0 && mx > 0 && !ticks.includes(0)) ticks.push(0);
  return ticks;
}

export default function RegimeDailyChart({ records }: { records: RegimeDaily[] }) {
  const [hover, setHover] = useState<{ di: number; si: number } | null>(null);

  if (records.length === 0) {
    return (
      <div className="flex h-[300px] items-center justify-center text-sm text-zinc-600">
        데이터 누적 중…
      </div>
    );
  }

  const data = [...records].reverse().map((d) => ({
    date: d.date,
    segs: [
      d.gap1,
      d.gap2At1000,
      d.gap2At1000 === null ? null : relative(d.gap2At1000, d.gap2Close),
    ] as (number | null)[],
  }));

  const W = 760;
  const H = 300;
  const padL = 44;
  const padR = 14;
  const padT = 16;
  const padB = 30;

  const all = data
    .flatMap((d) => d.segs.filter((v): v is number => v !== null))
    .concat(0);
  let mn = Math.min(...all);
  let mx = Math.max(...all);
  const sp = mx - mn || 1;
  mn -= sp * 0.12;
  mx += sp * 0.12;

  const y = (v: number) => padT + (1 - (v - mn) / (mx - mn)) * (H - padT - padB);
  const y0 = y(0);
  const plotW = W - padL - padR;
  const band = plotW / data.length;
  const bw = Math.min(10, band * 0.22);
  const gap = 2;
  const groupW = bw * 3 + gap * 2;

  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      className="w-full"
      role="img"
      onMouseLeave={() => setHover(null)}
    >
      {niceTicks(mn, mx).map((t) => (
        <g key={t}>
          <line
            x1={padL}
            x2={W - padR}
            y1={y(t)}
            y2={y(t)}
            stroke={t === 0 ? "#3f3f46" : "#1f1f23"}
          />
          <text x={padL - 6} y={y(t) + 3} textAnchor="end" fontSize={10} fill="#71717a">
            {fmtPct(t, t % 1 === 0 ? 0 : 1)}
          </text>
        </g>
      ))}

      {data.map((d, di) => {
        const startX = padL + band * di + (band - groupW) / 2;
        return (
          <g key={d.date}>
            {d.segs.map((v, si) =>
              v === null ? null : (
                <rect
                  key={si}
                  x={startX + si * (bw + gap)}
                  y={y(Math.max(0, v))}
                  width={bw}
                  height={Math.abs(y(v) - y0)}
                  fill={SEGMENTS[si].color}
                  rx={1}
                  opacity={hover && (hover.di !== di || hover.si !== si) ? 0.4 : 1}
                  onMouseEnter={() => setHover({ di, si })}
                />
              ),
            )}
            <text
              x={padL + band * di + band / 2}
              y={H - 10}
              textAnchor="middle"
              fontSize={10}
              fill="#71717a"
            >
              {fmtDate(d.date)}
            </text>
          </g>
        );
      })}

      {hover &&
        (() => {
          const d = data[hover.di];
          const v = d.segs[hover.si];
          if (v === null) return null;
          const seg = SEGMENTS[hover.si];
          const startX = padL + band * hover.di + (band - groupW) / 2;
          const bx = startX + hover.si * (bw + gap) + bw / 2;
          const tw = 150;
          const th = 50;
          let tx = bx - tw / 2;
          tx = Math.max(padL, Math.min(tx, W - padR - tw));
          let ty = y(Math.max(0, v)) - th - 6;
          if (ty < padT) ty = y(Math.min(0, v)) + 6;
          return (
            <g pointerEvents="none">
              <rect x={tx} y={ty} width={tw} height={th} rx={5} fill="#18181b" stroke="#3f3f46" />
              <text x={tx + 8} y={ty + 16} fontSize={11} fill="#a1a1aa">
                {fmtDate(d.date)} · {seg.key}
              </text>
              <text x={tx + 8} y={ty + 30} fontSize={10} fill="#71717a">
                {seg.from} → {seg.to}
              </text>
              <text
                x={tx + 8}
                y={ty + 44}
                fontSize={12}
                fontWeight="bold"
                fill={v >= 0 ? "#34d399" : "#fb7185"}
              >
                {fmtPct(v)}
              </text>
            </g>
          );
        })()}
    </svg>
  );
}
