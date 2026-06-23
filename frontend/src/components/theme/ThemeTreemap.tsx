import { useEffect, useRef, useState } from "react";
import { hierarchy, treemap, type HierarchyRectangularNode } from "d3-hierarchy";
import type { ThemeItem } from "../../types";

const HEIGHT = 520;
const HEADER = 18; // 테마(그룹) 헤더 높이

/** 등락률 → 색. 한국 관행: 상승 빨강, 하락 파랑, 0 근처 회색. null(과거 적재분)은 회색. */
function colorOf(rate: number | null): string {
  if (rate == null) return "rgb(63,63,70)"; // zinc-700
  const x = Math.max(-5, Math.min(5, rate)) / 5; // -1 ~ 1
  const base = [63, 63, 70];
  const target = x >= 0 ? [220, 38, 38] : [37, 99, 235]; // red-600 / blue-600
  const t = Math.abs(x);
  const c = base.map((b, i) => Math.round(b + (target[i] - b) * t));
  return `rgb(${c[0]},${c[1]},${c[2]})`;
}

interface Leaf {
  name: string;
  value: number;
  rate: number | null;
}

/** 등락률 색 범례 (-5 ~ +5%). */
export function TreemapLegend() {
  return (
    <div className="flex items-center gap-1 text-[10px] text-zinc-400">
      {[-5, -3, -1, 0, 1, 3, 5].map((s) => (
        <span key={s} className="flex items-center gap-0.5">
          <span className="inline-block w-5 h-3 rounded-[2px]" style={{ background: colorOf(s) }} />
          {s > 0 ? `+${s}` : s}
        </span>
      ))}
    </div>
  );
}

/** 테마=그룹, 종목=박스(크기 거래대금, 색 등락률)인 트리맵. */
export default function ThemeTreemap({ themes }: { themes: ThemeItem[] }) {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => setWidth(entries[0].contentRect.width));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const groups = themes
    .map((t) => ({
      name: t.name,
      children: t.stocks
        .filter((s) => s.tradingValue > 0)
        .map((s): Leaf => ({ name: s.stockName, value: s.tradingValue, rate: s.priceChangeRate })),
    }))
    .filter((g) => g.children.length > 0);

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const laidRoot: HierarchyRectangularNode<any> | null =
    width > 0
      ? // eslint-disable-next-line @typescript-eslint/no-explicit-any
        treemap<any>()
          .size([width, HEIGHT])
          .paddingTop(HEADER)
          .paddingInner(2)
          .round(true)(
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          hierarchy<any>({ name: "root", children: groups })
            .sum((d: { value?: number }) => d.value ?? 0)
            .sort((a, b) => (b.value ?? 0) - (a.value ?? 0)),
        )
      : null;

  return (
    <div ref={ref} className="relative w-full" style={{ height: groups.length ? HEIGHT : "auto" }}>
      {groups.length === 0 ? (
        <div className="text-sm text-zinc-500">표시할 데이터가 없습니다</div>
      ) : (
        laidRoot
          ?.descendants()
          .filter((n) => n.depth > 0)
          .map((n, i) => {
            const x = n.x0;
            const y = n.y0;
            const w = n.x1 - n.x0;
            const h = n.y1 - n.y0;
            if (n.depth === 1) {
              return (
                <div
                  key={`g-${i}`}
                  className="absolute overflow-hidden pointer-events-none"
                  style={{ left: x, top: y, width: w, height: h }}
                >
                  <div className="px-1.5 text-[11px] font-medium text-zinc-400 truncate leading-[18px]">
                    {n.data.name}
                  </div>
                </div>
              );
            }
            const d = n.data as Leaf;
            const pct = d.rate == null ? "" : `${d.rate > 0 ? "+" : ""}${d.rate.toFixed(2)}%`;
            return (
              <div
                key={`s-${i}`}
                className="absolute overflow-hidden flex flex-col items-center justify-center text-center text-white px-0.5"
                style={{ left: x, top: y, width: w, height: h, background: colorOf(d.rate) }}
                title={`${d.name} ${pct}`}
              >
                {w > 34 && h > 22 && (
                  <>
                    <span className="text-[11px] font-semibold leading-tight truncate max-w-full">
                      {d.name}
                    </span>
                    {pct && h > 34 && <span className="text-[10px] leading-tight opacity-90">{pct}</span>}
                  </>
                )}
              </div>
            );
          })
      )}
    </div>
  );
}
