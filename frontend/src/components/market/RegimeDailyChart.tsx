import { useCallback, useRef, useState } from "react";
import type { RegimeDaily } from "../../types";
import { REGIME_SEGMENTS, regimeSegments } from "../../lib/regimeSegments";

function fmtDate(iso: string): string {
  const d = new Date(iso);
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

function fmtPct(v: number, digits = 2): string {
  return `${v >= 0 ? "+" : ""}${v.toFixed(digits)}%`;
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

/**
 * 한 줄(최대 [columns]일) 구간별 막대. records는 시간순(오래된 게 왼쪽).
 * [domain]은 두 줄이 같은 척도를 쓰도록 외부에서 주입.
 */
export default function RegimeDailyChart({
  records,
  domain,
  columns,
}: {
  records: RegimeDaily[];
  domain: { mn: number; mx: number };
  columns: number;
}) {
  const [width, setWidth] = useState(760);
  const [hover, setHover] = useState<{ di: number; si: number } | null>(null);
  const obsRef = useRef<ResizeObserver | null>(null);

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

  const data = records.map((d) => ({ date: d.date, segs: regimeSegments(d) }));

  const W = width;
  const H = 180;
  const padL = 40;
  const padR = 14;
  const padT = 12;
  const padB = 24;

  const { mn, mx } = domain;
  const y = (v: number) => padT + (1 - (v - mn) / (mx - mn)) * (H - padT - padB);
  const y0 = y(0);
  const plotW = W - padL - padR;
  const band = plotW / columns;
  const bw = Math.min(16, band * 0.18);
  const gap = Math.min(3, band * 0.03);
  const groupW = bw * 4 + gap * 3;

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
                    fill={REGIME_SEGMENTS[si].color}
                    rx={1}
                    opacity={hover && (hover.di !== di || hover.si !== si) ? 0.4 : 1}
                    onMouseEnter={() => setHover({ di, si })}
                  />
                ),
              )}
              <text
                x={padL + band * di + band / 2}
                y={H - 8}
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
            const seg = REGIME_SEGMENTS[hover.si];
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
