import { useEffect, useRef, useState } from "react";
import { hierarchy, treemap, type HierarchyRectangularNode } from "d3-hierarchy";
import type { ThemeItem } from "../../types";

const HEIGHT = 520;
const HEADER = 24; // 테마(그룹) 헤더 높이

/** 등락률 → 색. 한국 관행: 상승 적색, 하락 청색, 0 근처 슬레이트. null(과거 적재분)은 중립. */
function colorOf(rate: number | null): string {
  if (rate == null) return "rgb(51,54,63)"; // 중립 슬레이트
  const x = Math.max(-5, Math.min(5, rate)) / 5; // -1 ~ 1
  const t = Math.pow(Math.abs(x), 0.8); // 작은 변동은 더 은은하게
  const neutral = [43, 47, 58]; // #2b2f3a
  const target = x >= 0 ? [183, 76, 68] : [66, 99, 173]; // 차분한 적/청
  const c = neutral.map((b, i) => Math.round(b + (target[i] - b) * t));
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

  // 한 종목은 여러 테마에 속하므로, 면적·박스가 중복되지 않게 가장 상위 랭크 테마에만 배정한다.
  // (낮은 랭크 테마가 같은 종목만으로 채워졌다면 비게 되어 자연히 사라진다.)
  const seen = new Set<string>();
  const groups = [...themes]
    .sort((a, b) => a.rank - b.rank)
    .map((t) => ({
      name: t.name,
      children: t.stocks
        .filter((s) => {
          if (s.tradingValue <= 0 || seen.has(s.stockCode)) return false;
          seen.add(s.stockCode);
          return true;
        })
        .map((s): Leaf => ({ name: s.stockName, value: s.tradingValue, rate: s.priceChangeRate })),
    }))
    .filter((g) => g.children.length > 0);

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const laidRoot: HierarchyRectangularNode<any> | null =
    width > 0
      ? // eslint-disable-next-line @typescript-eslint/no-explicit-any
        treemap<any>()
          .size([width, HEIGHT])
          .paddingOuter(4)
          .paddingTop(HEADER)
          .paddingInner(3)
          .round(true)(
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          hierarchy<any>({ name: "root", children: groups })
            // 면적은 √거래대금 — 삼성전자·SK하이닉스 같은 초대형주가 화면을 독식해
            // 작은 테마가 안 보이는 것을 완화한다(크기 차이를 압축, 순서는 유지).
            .sum((d: { value?: number }) => (d.value ? Math.sqrt(d.value) : 0))
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
                  className="absolute overflow-hidden pointer-events-none rounded-md bg-white/[0.02] ring-1 ring-white/[0.05]"
                  style={{ left: x, top: y, width: w, height: h }}
                >
                  <div
                    className="px-2 flex items-center text-[13px] font-semibold text-zinc-300 truncate"
                    style={{ height: HEADER }}
                  >
                    {n.data.name}
                  </div>
                </div>
              );
            }
            const d = n.data as Leaf;
            const pct = d.rate == null ? "" : `${d.rate > 0 ? "+" : ""}${d.rate.toFixed(2)}%`;
            // 박스가 클수록 글씨도 크게 (가로·세로에 비례, 상·하한 클램프)
            const nameSize = Math.max(11, Math.min(w * 0.2, h * 0.42, 40));
            const pctSize = Math.max(10, Math.min(nameSize * 0.7, 24));
            const showName = w > 28 && h > 16;
            const showPct = !!pct && h > nameSize + pctSize + 6;
            return (
              <div
                key={`s-${i}`}
                className="absolute overflow-hidden flex flex-col items-center justify-center text-center text-white px-0.5 rounded-[3px]"
                style={{
                  left: x,
                  top: y,
                  width: w,
                  height: h,
                  background: colorOf(d.rate),
                  boxShadow: "inset 0 0 0 1px rgba(255,255,255,0.05)",
                  textShadow: "0 1px 2px rgba(0,0,0,0.35)",
                }}
                title={`${d.name} ${pct}`}
              >
                {showName && (
                  <>
                    <span
                      className="font-semibold leading-none truncate max-w-full"
                      style={{ fontSize: nameSize }}
                    >
                      {d.name}
                    </span>
                    {showPct && (
                      <span className="leading-none opacity-90 mt-1" style={{ fontSize: pctSize }}>
                        {pct}
                      </span>
                    )}
                  </>
                )}
              </div>
            );
          })
      )}
    </div>
  );
}
