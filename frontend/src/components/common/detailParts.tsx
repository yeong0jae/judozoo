import type { ReactNode } from "react";

/** 상세 패널 조각 — 국내(`StockDetailPanel`)와 해외(`OverseasStockDetailPanel`)가 같이 쓴다. */

/** 차트 높이 — 지수·수급 상세와 같다. 차트가 autoSize라 컨테이너 높이만 바꾸면 된다. */
export const CHART_H = "h-[21.25rem] 2xl:h-[26rem]";

export function ChartEmpty({ children }: { children: ReactNode }) {
  return <div className={`${CHART_H} flex items-center justify-center text-xs text-zinc-600`}>{children}</div>;
}

export function Chevron({ dir }: { dir: "left" | "right" }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d={dir === "left" ? "M15 6l-6 6 6 6" : "M9 6l6 6-6 6"} />
    </svg>
  );
}

/** 탭 두 개짜리 세그먼트 — 지수·수급 화면의 토글과 같은 모양. */
export function Segmented<T extends string>({
  label,
  items,
  value,
  onChange,
}: {
  label: string;
  items: [T, string][];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <div role="tablist" aria-label={label} className="flex shrink-0 rounded-xl bg-zinc-800 p-0.5 text-xs">
      {items.map(([key, text]) => (
        <button
          key={key}
          type="button"
          role="tab"
          aria-selected={value === key}
          onClick={() => onChange(key)}
          className={`rounded-lg px-3 py-1.5 transition-colors ${
            value === key ? "bg-elevated font-medium text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
          }`}
        >
          {text}
        </button>
      ))}
    </div>
  );
}

// ============================================================
// 주도주 조건
// ============================================================

export function LeadingConditions({ results }: { results: { filterName: string; criteriaDescription: string; actualValue: string; passed: boolean }[] }) {
  const passedCount = results.filter((r) => r.passed).length;
  return (
    <section className="@container flex flex-col gap-3">
      <div className="flex items-baseline justify-between">
        <h3 className="text-[15px] font-bold text-zinc-100">주도주 조건</h3>
        <span className="num text-xs text-zinc-400">
          <span className="font-bold text-emerald-400">{passedCount}</span> / {results.length} 통과
        </span>
      </div>
      {/* 한 칸이 조건 하나 — 어디서 떨어졌는지가 순서로 읽힌다(판별력이 큰 조건이 앞) */}
      <div className="flex h-1.5 gap-0.5 overflow-hidden rounded-full bg-zinc-850" aria-hidden>
        {results.map((r) => (
          <span key={r.filterName} className={`flex-1 ${r.passed ? "bg-emerald-400/85" : "bg-red-400/90"}`} />
        ))}
      </div>
      {/* 두 열은 칸이 넓을 때만 — 좁은 두 열이면 이름이 글자 단위로 꺾인다(실측값이 "1조 2,406억 5,300만원"처럼 길다) */}
      <ul className="grid grid-cols-1 gap-1.5 @xl:grid-cols-2">
        {results.map((r) => (
          <li key={r.filterName} className="flex items-center gap-2.5 rounded-xl bg-zinc-900 px-3 py-2.5">
            <span
              className={`inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-md ${
                r.passed ? "bg-emerald-400/10 text-emerald-400" : "bg-red-400/10 text-red-400"
              }`}
            >
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                <path d={r.passed ? "M5 12l5 5 9-10" : "M7 7l10 10M17 7L7 17"} />
              </svg>
              <span className="sr-only">{r.passed ? "통과" : "미달"}</span>
            </span>
            <span className="flex min-w-0 flex-1 flex-col gap-px">
              <span className="break-keep text-xs font-semibold text-zinc-300">{r.filterName}</span>
              <span className="truncate text-[11px] text-zinc-500">{r.criteriaDescription}</span>
            </span>
            <span className={`num shrink-0 whitespace-nowrap text-xs font-semibold ${r.passed ? "text-zinc-100" : "text-red-400"}`}>
              {r.actualValue}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

