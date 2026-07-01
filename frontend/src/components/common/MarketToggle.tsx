export type StockMarket = "domestic" | "overseas";

const TABS: { key: StockMarket; label: string }[] = [
  { key: "domestic", label: "국내" },
  { key: "overseas", label: "해외" },
];

/** 국내/해외 전환 — 언더라인 탭. 활성은 중립(흰색) 언더라인(파랑=하락과 혼동 방지). 하단 보더가 리스트와의 구분선. */
export default function MarketToggle({
  value,
  onChange,
}: {
  value: StockMarket;
  onChange: (market: StockMarket) => void;
}) {
  return (
    <div className="flex gap-6 border-b border-white/[0.08]">
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
  );
}
