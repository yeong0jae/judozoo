import { Fragment, useState } from "react";
import { motion } from "motion/react";
import DateNavigator, { todayStr } from "../components/common/DateNavigator";
import EmptyState from "../components/common/EmptyState";
import Skeleton from "../components/common/Skeleton";
import { useSignalAnalysis } from "../api/queries";
import { useLabelSignals } from "../api/mutations";
import type {
  SignalKind,
  SignalKindStat,
  SignalMetrics,
  StockSignalGroup,
  TimeBucket,
} from "../types";

const KIND_LABEL: Record<SignalKind, string> = {
  BREAKOUT: "돌파",
  BREAKOUT_IMMINENT: "임박",
  SPIKE_BUY: "매수 스파이크",
  SPIKE_SELL: "매도 스파이크",
  SPIKE_FLAT: "보합 스파이크",
};

const BUCKET_LABEL: Record<TimeBucket, string> = {
  PRE_NXT: "오전 NXT",
  EARLY: "장초반",
  MID: "장중",
  LATE: "막판",
  POST_NXT: "오후 NXT",
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
  const [byTime, setByTime] = useState(false);
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
          <div className="flex justify-end">
            <label className="flex items-center gap-1.5 text-xs text-zinc-400 cursor-pointer">
              <input
                type="checkbox"
                checked={byTime}
                onChange={(e) => setByTime(e.target.checked)}
                className="accent-zinc-500"
              />
              시간대로 나누기
            </label>
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            <StatTable title="그날 성격 (표본 적음 — 참고용)" stats={data.dayStats} byTime={byTime} />
            <StatTable title="전체 누적 (승률 사전)" stats={data.overallStats} byTime={byTime} />
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

const DIST_LABEL = ["≤-5%", "-5~-2", "-2~-0.5", "±0.5", "+0.5~2", "+2~5", "≥+5%"];
// 음수 구간 파랑, 중앙 회색, 양수 구간 빨강 (한국식)
const DIST_COLOR = [
  "bg-blue-500",
  "bg-blue-400/70",
  "bg-blue-400/40",
  "bg-zinc-600",
  "bg-red-400/40",
  "bg-red-400/70",
  "bg-red-500",
];

/** +20m 수익률 7구간 분포 막대 — 평균 뒤의 모양(대칭/꼬리)을 본다. */
function DistBar({ dist }: { dist: number[] }) {
  const max = Math.max(1, ...dist);
  const total = dist.reduce((a, b) => a + b, 0);
  return (
    <div className="px-3 py-2 bg-zinc-900/40 space-y-0.5">
      <div className="text-[10px] text-zinc-600 mb-1">+20m 수익률 분포 · {total}건</div>
      {dist.map((c, i) => (
        <div key={i} className="flex items-center gap-2 text-[10px]">
          <span className="w-14 text-right text-zinc-500">{DIST_LABEL[i]}</span>
          <div className="flex-1 h-3 rounded bg-zinc-800/40">
            <div
              className={`h-3 rounded ${DIST_COLOR[i]}`}
              style={{ width: `${(c / max) * 100}%` }}
            />
          </div>
          <span className="w-6 text-zinc-500">{c}</span>
        </div>
      ))}
    </div>
  );
}

/** 메트릭 셀들 (+1m ~ 승률) — 종류 행·시간대 행 공통. */
function MetricCells({ m }: { m: SignalMetrics }) {
  return (
    <>
      <td><Ret v={m.avg1m} /></td>
      <td><Ret v={m.avg2m} /></td>
      <td><Ret v={m.avg20m} /></td>
      <td><Ret v={m.avg2h} /></td>
      <td><Ret v={m.avgClose} /></td>
      <td><Ret v={m.avgMfe} /></td>
      <td><Ret v={m.avgMae} /></td>
      <td className="text-zinc-400">
        {m.winRate20m == null ? "—" : `${m.winRate20m.toFixed(0)}%`}
      </td>
    </>
  );
}

function StatTable({
  title,
  stats,
  byTime,
}: {
  title: string;
  stats: SignalKindStat[];
  byTime: boolean;
}) {
  const [open, setOpen] = useState<string | null>(null);
  const toggle = (key: string) => setOpen((k) => (k === key ? null : key));
  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900/50 p-3">
      <h2 className="text-sm font-semibold text-zinc-300 mb-2">
        {title}
        <span className="ml-2 text-[10px] font-normal text-zinc-600">행 클릭 → +20m 분포</span>
      </h2>
      {stats.length === 0 ? (
        <p className="text-xs text-zinc-600">데이터 없음</p>
      ) : (
        <table className="w-full text-xs">
          <thead className="text-zinc-500">
            <tr className="text-right">
              <th className="text-left font-normal py-1">종류</th>
              <th className="font-normal">건수</th>
              <th className="font-normal">+1m</th>
              <th className="font-normal">+2m</th>
              <th className="font-normal">+20m</th>
              <th className="font-normal">+2h</th>
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
                <Fragment key={s.kind}>
                  <tr
                    onClick={() => toggle(s.kind)}
                    className="text-right border-t border-zinc-800/60 cursor-pointer hover:bg-zinc-800/30"
                  >
                    <td className="text-left py-1">
                      <span className={avoid ? "text-amber-400" : "text-zinc-200"}>
                        {KIND_LABEL[s.kind]}
                      </span>
                      {avoid && <span className="ml-1 text-[10px] text-amber-500">회피</span>}
                    </td>
                    <td className="text-zinc-500">
                      {s.labeled}/{s.count}
                    </td>
                    <MetricCells m={s.metrics} />
                  </tr>
                  {open === s.kind && (
                    <tr>
                      <td colSpan={10}>
                        <DistBar dist={s.metrics.dist20m} />
                      </td>
                    </tr>
                  )}
                  {byTime &&
                    s.byBucket.map((b) => {
                      const key = `${s.kind}-${b.bucket}`;
                      return (
                        <Fragment key={key}>
                          <tr
                            onClick={() => toggle(key)}
                            className="text-right text-zinc-500 cursor-pointer hover:bg-zinc-800/30"
                          >
                            <td className="text-left pl-4 py-0.5">└ {BUCKET_LABEL[b.bucket]}</td>
                            <td>
                              {b.labeled}/{b.count}
                            </td>
                            <MetricCells m={b.metrics} />
                          </tr>
                          {open === key && (
                            <tr>
                              <td colSpan={10}>
                                <DistBar dist={b.metrics.dist20m} />
                              </td>
                            </tr>
                          )}
                        </Fragment>
                      );
                    })}
                </Fragment>
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
            <th className="font-normal">+1m</th>
            <th className="font-normal">+2m</th>
            <th className="font-normal">+20m</th>
            <th className="font-normal">+2h</th>
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
                <td><Ret v={r.ret1m} /></td>
                <td><Ret v={r.ret2m} /></td>
                <td><Ret v={r.ret20m} /></td>
                <td><Ret v={r.ret2h} /></td>
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
