export type StockMarket = "domestic" | "overseas";

const TABS: { key: StockMarket; label: string }[] = [
  { key: "domestic", label: "국내" },
  { key: "overseas", label: "해외" },
];

/** 국내/해외 전환 토글 — StockDetailPanel의 [상세|1분봉|일봉] 토글과 동일 디자인. */
export default function MarketToggle({
  value,
  onChange,
}: {
  value: StockMarket;
  onChange: (market: StockMarket) => void;
}) {
  return (
    <div className="flex rounded-lg bg-white/[0.04] p-0.5 text-sm w-fit">
      {TABS.map((t) => (
        <button
          key={t.key}
          type="button"
          onClick={() => onChange(t.key)}
          className={`px-4 py-1.5 rounded-md transition-colors ${
            value === t.key
              ? "bg-white/[0.1] text-zinc-100"
              : "text-zinc-500 hover:text-zinc-300"
          }`}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}
