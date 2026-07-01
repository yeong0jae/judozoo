import type { ReactNode } from "react";

export type StockMarket = "domestic" | "overseas";

const TABS: { key: StockMarket; label: string }[] = [
  { key: "domestic", label: "국내" },
  { key: "overseas", label: "해외" },
];

/**
 * 국내/해외 전환 — 언더라인 탭. 활성은 중립(흰색) 언더라인(파랑=하락과 혼동 방지). 하단 보더가 리스트와의 구분선.
 * [trailing]을 주면 같은 줄 우측에 배치(예: 등락률 필터) — 좁으면 아래로 감싼다.
 */
export default function MarketToggle({
  value,
  onChange,
  trailing,
}: {
  value: StockMarket;
  onChange: (market: StockMarket) => void;
  trailing?: ReactNode;
}) {
  return (
    <div className="flex items-end justify-between gap-x-4 gap-y-2 flex-wrap border-b border-white/[0.08]">
      <div className="flex gap-6">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => onChange(t.key)}
            className={`pb-2.5 -mb-px border-b-2 text-sm font-medium transition-colors ${
              value === t.key
                ? "border-zinc-100 text-zinc-100"
                : "border-transparent text-zinc-500 hover:text-zinc-300"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>
      {trailing && <div className="pb-2 shrink-0">{trailing}</div>}
    </div>
  );
}
