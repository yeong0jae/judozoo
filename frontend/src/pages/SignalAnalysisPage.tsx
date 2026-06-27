import { useState } from "react";
import { motion } from "motion/react";
import DateNavigator, { todayStr } from "../components/common/DateNavigator";
import EmptyState from "../components/common/EmptyState";
import Skeleton from "../components/common/Skeleton";
import { useSignalAnalysis } from "../api/queries";
import { useLabelSignals } from "../api/mutations";
import type { SignalKind, SignalKindStat, StockSignalGroup } from "../types";

const KIND_LABEL: Record<SignalKind, string> = {
  BREAKOUT: "돌파",
  BREAKOUT_IMMINENT: "임박",
  SPIKE_BUY: "매수 스파이크",
  SPIKE_SELL: "매도 스파이크",
  SPIKE_FLAT: "보합 스파이크",
};

/** 매도 스파이크는 진입이 아니라 회피 신호 — 통계도 "승률"이 아니라 주의 표식. */
const AVOID_KINDS: SignalKind[] = ["SPIKE_SELL"];

/** HH:mm:ss → HH:mm (타임존 변환 없이 문자열에서). */
const hhmm = (iso: string) => iso.slice(11, 16);

/** 수익률 → "+1.2%" / "-0.4%" / "—"(측정 불가). */
function pct(v: number | null): string {
  if (v == null) return "—";
  return `${v >= 0 ? "+" : ""}${v.toFixed(1)}%`;
}

/** 등락 색 — 한국식(양수 빨강, 음수 파랑). null은 흐리게. */
function retClass(v: number | null): string {
  if (v == null) return "text-zinc-600";
  return v >= 0 ? "text-red-400" : "text-blue-400";
}

function Ret({ v }: { v: number | null }) {
  return <span className={retClass(v)}>{pct(v)}</span>;
}

export default function SignalAnalysisPage() {
  const [date, setDate] = useState(todayStr());
  const { data, isLoading } = useSignalAnalysis(date);
  const label = useLabelSignals();

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div>
          <h1 className="text-xl font-bold text-zinc-100">시그널 분석</h1>
          <p className="mt-1 text-sm text-zinc-500">
            실시간 로그에 사후 수익률을 붙여 복기 — 종목 여정 · 종류별 성과(매수/매도 스파이크 분리)
          </p>
        </div>
        <div className="flex items-center gap-3">
          <DateNavigator date={date} onChange={setDate} />
          <motion.button
            onClick={() => label.mutate(date)}
            disabled={label.isPending}
            whileTap={{ scale: 0.97 }}
            className="px-3 py-1.5 text-sm rounded bg-zinc-800 text-zinc-200 hover:bg-zinc-700 disabled:opacity-50"
          >
            {label.isPending ? "라벨링 중…" : "이 날짜 라벨링"}
          </motion.button>
        </div>
      </div>

      {isLoading ? (
        <Skeleton className="h-40" />
      ) : !data || data.stocks.length === 0 ? (
        <EmptyState message={`${date} 신호가 없습니다 — 라벨링은 신호가 쌓인 날에만 의미가 있어요`} />
      ) : (
        <>
          <div className="grid gap-4 lg:grid-cols-2">
            <StatTable title="그날 성격 (표본 적음 — 참고용)" stats={data.dayStats} />
            <StatTable title="전체 누적 (승률 사전)" stats={data.overallStats} />
          </div>
          <div className="space-y-3">
            {data.stocks.map((g) => (
              <StockCard key={g.stockCode} group={g} />
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function StatTable({ title, stats }: { title: string; stats: SignalKindStat[] }) {
  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900/50 p-3">
      <h2 className="text-sm font-semibold text-zinc-300 mb-2">{title}</h2>
      {stats.length === 0 ? (
        <p className="text-xs text-zinc-600">데이터 없음</p>
      ) : (
        <table className="w-full text-xs">
          <thead className="text-zinc-500">
            <tr className="text-right">
              <th className="text-left font-normal py-1">종류</th>
              <th className="font-normal">건수</th>
              <th className="font-normal">+5m</th>
              <th className="font-normal">+10m</th>
              <th className="font-normal">+30m</th>
              <th className="font-normal">종가</th>
              <th className="font-normal">고점</th>
              <th className="font-normal">저점</th>
              <th className="font-normal">승률</th>
            </tr>
          </thead>
          <tbody className="text-zinc-300">
            {stats.map((s) => {
              const avoid = AVOID_KINDS.includes(s.kind);
              return (
                <tr key={s.kind} className="text-right border-t border-zinc-800/60">
                  <td className="text-left py-1">
                    <span className={avoid ? "text-amber-400" : "text-zinc-200"}>
                      {KIND_LABEL[s.kind]}
                    </span>
                    {avoid && <span className="ml-1 text-[10px] text-amber-500">회피</span>}
                  </td>
                  <td className="text-zinc-500">
                    {s.labeled}/{s.count}
                  </td>
                  <td><Ret v={s.avg5m} /></td>
                  <td><Ret v={s.avg10m} /></td>
                  <td><Ret v={s.avg30m} /></td>
                  <td><Ret v={s.avgClose} /></td>
                  <td><Ret v={s.avgMfe} /></td>
                  <td><Ret v={s.avgMae} /></td>
                  <td className="text-zinc-400">
                    {s.winRate10m == null ? "—" : `${s.winRate10m.toFixed(0)}%`}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}

function StockCard({ group }: { group: StockSignalGroup }) {
  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900/50 overflow-hidden">
      <div className="flex items-center justify-between px-3 py-2 bg-zinc-800/40">
        <div className="flex items-center gap-2 text-sm">
          <span className="font-semibold text-zinc-100">{group.stockName}</span>
          <span className="text-xs text-zinc-600">{group.stockCode}</span>
          {group.theme && (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-400">
              {group.theme}
            </span>
          )}
        </div>
        <div className="flex items-center gap-3 text-xs">
          <span className="text-zinc-500">
            고점 <Ret v={group.bestMfe} />
          </span>
          <span className="text-zinc-500">
            종가 <Ret v={group.closeRet} />
          </span>
        </div>
      </div>
      <table className="w-full text-xs">
        <thead className="text-zinc-600">
          <tr className="text-right">
            <th className="text-left font-normal px-3 py-1">시각</th>
            <th className="text-left font-normal">신호</th>
            <th className="font-normal">현재가</th>
            <th className="font-normal">+5m</th>
            <th className="font-normal">+10m</th>
            <th className="font-normal">+30m</th>
            <th className="font-normal">종가</th>
            <th className="font-normal">고점</th>
            <th className="font-normal px-3">저점</th>
          </tr>
        </thead>
        <tbody className="text-zinc-300">
          {group.signals.map((r, i) => {
            const avoid = AVOID_KINDS.includes(r.kind);
            return (
              <tr key={i} className="text-right border-t border-zinc-800/60">
                <td className="text-left px-3 py-1 text-zinc-400">{hhmm(r.occurredAt)}</td>
                <td className="text-left">
                  <span className={avoid ? "text-amber-400" : "text-zinc-200"}>
                    {KIND_LABEL[r.kind]}
                  </span>
                  {r.spikeRatio != null && (
                    <span className="ml-1 text-zinc-600">{r.spikeRatio.toFixed(1)}배</span>
                  )}
                </td>
                <td className="text-zinc-400">{r.currentPrice.toLocaleString()}</td>
                <td><Ret v={r.ret5m} /></td>
                <td><Ret v={r.ret10m} /></td>
                <td><Ret v={r.ret30m} /></td>
                <td><Ret v={r.retClose} /></td>
                <td><Ret v={r.mfe} /></td>
                <td className="px-3"><Ret v={r.mae} /></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
