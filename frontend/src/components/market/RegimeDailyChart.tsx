import type { RegimeDaily } from "../../types";

function fmtDate(iso: string): string {
  const d = new Date(iso);
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

/**
 * 최근 N일 — 날짜별 아침 갭(Gap1) vs 본장 결과(Gap2 종가) 그룹 막대.
 * 라이브러리 없이 SVG로 그린다. records는 최신순으로 들어와 시간순으로 뒤집어 그린다.
 */
export default function RegimeDailyChart({ records }: { records: RegimeDaily[] }) {
  if (records.length === 0) {
    return (
      <div className="flex h-[260px] items-center justify-center text-sm text-zinc-600">
        데이터 누적 중…
      </div>
    );
  }

  const data = [...records].reverse(); // 최신순 → 시간순(왼→오)
  const W = 720;
  const H = 260;
  const padL = 40;
  const padR = 12;
  const padT = 14;
  const padB = 28;

  const vals = data.flatMap((d) => [d.gap1, d.gap2Close]);
  let mx = Math.max(...vals, 0);
  let mn = Math.min(...vals, 0);
  const sp = mx - mn || 1;
  mx += sp * 0.1;
  mn -= sp * 0.1;

  const y = (v: number) => padT + (1 - (v - mn) / (mx - mn)) * (H - padT - padB);
  const y0 = y(0);
  const band = (W - padL - padR) / data.length;
  const bw = Math.min(16, band * 0.32);

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img">
      <line x1={padL} x2={W - padR} y1={y0} y2={y0} stroke="#3f3f46" />
      <text x={padL - 6} y={y0 + 3} textAnchor="end" fontSize={10} fill="#71717a">
        0%
      </text>
      {data.map((d, i) => {
        const cx = padL + band * i + band / 2;
        const g1h = Math.abs(y(d.gap1) - y0);
        const g2 = d.gap2Close;
        const g2h = Math.abs(y(g2) - y0);
        const g2color = g2 >= 0 ? "#34d399" : "#fb7185";
        return (
          <g key={d.date}>
            <rect
              x={cx - bw - 2}
              y={y(Math.max(0, d.gap1))}
              width={bw}
              height={g1h}
              fill="#38bdf8"
              rx={1}
            />
            <rect
              x={cx + 2}
              y={y(Math.max(0, g2))}
              width={bw}
              height={g2h}
              fill={g2color}
              rx={1}
            />
            <text x={cx} y={H - 10} textAnchor="middle" fontSize={10} fill="#71717a">
              {fmtDate(d.date)}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
