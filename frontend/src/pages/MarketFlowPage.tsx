import { useRegimeDaily } from "../api/queries";
import RegimePanel from "../components/market/RegimePanel";
import RegimeDailyChart from "../components/market/RegimeDailyChart";

function Swatch({ color }: { color: string }) {
  return (
    <span
      className="mr-1 inline-block h-2.5 w-2.5 rounded-sm align-middle"
      style={{ background: color }}
    />
  );
}

export default function MarketFlowPage() {
  const dailyQ = useRegimeDaily();

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-zinc-100">시장 흐름</h1>
      <RegimePanel />
      <div className="rounded-xl border border-zinc-800 bg-zinc-900/60 p-4">
        <h2 className="mb-1 text-sm font-semibold text-zinc-300">
          최근 10일 — 오전 NXT 변동 vs 오후 KRX 마감
        </h2>
        <p className="mb-2 text-xs text-zinc-600">
          오전 NXT 변동(전일종가→아침NXT) 과 오후 KRX 마감(아침NXT→종가) 비교
        </p>
        <RegimeDailyChart records={dailyQ.data ?? []} />
        <div className="mt-2 flex gap-4 text-xs text-zinc-500">
          <span>
            <Swatch color="#38bdf8" />오전 NXT 변동
          </span>
          <span>
            <Swatch color="#34d399" />오후 KRX 마감 + / <Swatch color="#fb7185" />−
          </span>
        </div>
      </div>
    </div>
  );
}
