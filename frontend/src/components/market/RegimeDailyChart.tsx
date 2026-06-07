import { useCallback, useRef, useState } from "react";
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
  const [width, setWidth] = useState(760);
  const [hover, setHover] = useState<{ di: number; si: number } | null>(null);
  const obsRef = useRef<ResizeObserver | null>(null);

  // 콜백 ref — 차트 div가 실제로 마운트될 때 옵저버를 붙인다(로딩 후 마운트되는 케이스 대응).
  const measureRef = useCallback((node: HTMLDivElement | null) => {
    obsRef.current?.disconnect();
    if (!node) return;
    const ro = new ResizeObserver((entries) => {
      const w = entries[0].contentRect.width;
      if (w > 0) setWidth(w);
    });
    ro.observe(node);
    obsRef.current = ro;
  }, []);

  if (records.length === 0) {
    return (
      <div
        ref={measureRef}
        className="flex h-[260px] w-full items-center justify-center text-sm text-zinc-600"
      >
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

  const W = width;
  const H = 260;
  const padL = 40;
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
  const bw = Math.min(22, band * 0.24);
  const gap = Math.min(4, band * 0.04);
  const groupW = bw * 3 + gap * 2;

  return (
    <div ref={measureRef} className="w-full">
      <svg
        width={W}
        height={H}
        viewBox={`0 0 ${W} ${H}`}
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
            <text x={padL - 6} y={y(t) + 3} textAnchor="end" fontSize={11} fill="#71717a">
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
                fontSize={11}
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
            const tw = 160;
            const th = 52;
            let tx = bx - tw / 2;
            tx = Math.max(padL, Math.min(tx, W - padR - tw));
            let ty = y(Math.max(0, v)) - th - 6;
            if (ty < padT) ty = y(Math.min(0, v)) + 6;
            return (
              <g pointerEvents="none">
                <rect x={tx} y={ty} width={tw} height={th} rx={5} fill="#18181b" stroke="#3f3f46" />
                <text x={tx + 10} y={ty + 17} fontSize={12} fill="#a1a1aa">
                  {fmtDate(d.date)} · {seg.key}
                </text>
                <text x={tx + 10} y={ty + 32} fontSize={11} fill="#71717a">
                  {seg.from} → {seg.to}
                </text>
                <text
                  x={tx + 10}
                  y={ty + 47}
                  fontSize={13}
                  fontWeight="bold"
                  fill={v >= 0 ? "#34d399" : "#fb7185"}
                >
                  {fmtPct(v)}
                </text>
              </g>
            );
          })()}
      </svg>
    </div>
  );
}
