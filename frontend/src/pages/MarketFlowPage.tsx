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
          최근 10일 — 구간별 변동
        </h2>
        <p className="mb-3 text-xs leading-relaxed text-zinc-600">
          하루를 세 구간으로 나눠 <b className="text-zinc-400">직전 시점 대비</b> 변동을
          보여줍니다. 거래대금 상위 30종목(ETF 제외)의 등락률 기준.
          <br />
          오전 NXT = 전일종가→08:15(아침 NXT) · 오전장 = 08:15→10:00 · 오후 마감 =
          10:00→15:30. 막대에 마우스를 올리면 상세가 나옵니다.
        </p>
        <RegimeDailyChart records={dailyQ.data ?? []} />
        <div className="mt-2 flex gap-4 text-xs text-zinc-500">
          <span>
            <Swatch color="#38bdf8" />오전 NXT
          </span>
          <span>
            <Swatch color="#a78bfa" />오전장
          </span>
          <span>
            <Swatch color="#fbbf24" />오후 마감
          </span>
        </div>
      </div>
    </div>
  );
}
