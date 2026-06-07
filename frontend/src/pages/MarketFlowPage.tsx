import { useEffect, useState } from "react";
import { useRegimeSeries } from "../api/queries";
import { useStompSubscription } from "../ws/useStompSubscription";
import type { RegimePoint, RegimeSnapshot } from "../types";
import RegimePanel from "../components/market/RegimePanel";
import RegimeChart from "../components/market/RegimeChart";

export default function MarketFlowPage() {
  const seriesQ = useRegimeSeries();
  const [points, setPoints] = useState<RegimePoint[]>([]);

  useEffect(() => {
    if (seriesQ.data) setPoints(seriesQ.data);
  }, [seriesQ.data]);

  // 실시간으로 들어오는 스냅샷을 시계열에 이어붙임 (중복 asOf는 무시)
  useStompSubscription<RegimeSnapshot>("/topic/regime", (snap) => {
    setPoints((prev) =>
      prev.some((p) => p.asOf === snap.asOf)
        ? prev
        : [...prev, { asOf: snap.asOf, gap1: snap.gap1, gap2: snap.gap2 }],
    );
  });

  const chartPoints = points
    .filter((p) => p.gap2 !== null)
    .map((p) => ({ t: Date.parse(p.asOf), gap2: p.gap2 as number }));
  const gap1 = points.length ? points[points.length - 1].gap1 : null;

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-zinc-100">시장 흐름</h1>
      <RegimePanel />
      <div className="rounded-xl border border-zinc-800 bg-zinc-900/60 p-4">
        <h2 className="mb-2 text-sm font-semibold text-zinc-300">
          당일 본장 흐름 — 아침 NXT(08:15) 대비
        </h2>
        <RegimeChart points={chartPoints} gap1={gap1} />
      </div>
    </div>
  );
}
