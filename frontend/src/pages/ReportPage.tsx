import { useMemo, useState } from "react";
import { mockCommandDetails, mockDailyReport } from "../mocks/data";
import type { CloseReason, DailyTrading } from "../types";
import {
  formatDateTime,
  formatDuration,
  formatKRW,
  formatPct,
} from "../lib/format";
import CloseReasonBadge from "../components/common/CloseReasonBadge";
import EmptyState from "../components/common/EmptyState";
import ProfitText from "../components/common/ProfitText";
import DetailPanel from "../components/trading/DetailPanel";
import { useSettings } from "../settings/settings";

type SortKey = "closedAt" | "profitRate";
type PnLFilter = "all" | "win" | "loss";

const today = new Date().toISOString().slice(0, 10);

export default function ReportPage() {
  const settings = useSettings();
  // 5-B-1: 오늘 데이터만 사용 (mockDailyReport 중 오늘 분량). 실제로는 today를 별도로 두지만,
  // mockDailyReport에 오늘 항목이 없으면 가장 최근 날짜를 보여준다.
  const availableDates = useMemo(
    () =>
      Array.from(
        new Set(
          mockDailyReport.map((r) =>
            (r.closedAt ?? r.createdAt).slice(0, 10),
          ),
        ),
      ).sort(),
    [],
  );
  const defaultDate = availableDates.includes(today)
    ? today
    : availableDates[availableDates.length - 1];

  const [date, setDate] = useState<string>(defaultDate);
  const [sortKey, setSortKey] = useState<SortKey>("closedAt");
  const [pnlFilter, setPnLFilter] = useState<PnLFilter>("all");
  const [reasonFilter, setReasonFilter] = useState<Set<CloseReason>>(
    new Set(),
  );
  const [drillDownId, setDrillDownId] = useState<number | null>(null);

  const dayRows = useMemo(() => {
    return mockDailyReport
      .filter(
        (r) => (r.closedAt ?? r.createdAt).slice(0, 10) === date,
      )
      .filter((r) => {
        if (pnlFilter === "win") return r.profitAmount > 0;
        if (pnlFilter === "loss") return r.profitAmount < 0;
        return true;
      })
      .filter(
        (r) =>
          reasonFilter.size === 0 ||
          (r.closeReason && reasonFilter.has(r.closeReason)),
      )
      .sort(sortFn(sortKey));
  }, [date, sortKey, pnlFilter, reasonFilter]);

  const allDayRows = useMemo(
    () =>
      mockDailyReport.filter(
        (r) => (r.closedAt ?? r.createdAt).slice(0, 10) === date,
      ),
    [date],
  );

  const summary = useMemo(() => computeSummary(allDayRows), [allDayRows]);
  const unclosedCount = allDayRows.filter(
    (r) => r.closeReason === "UNCLOSED",
  ).length;

  const drillDown = drillDownId ? mockCommandDetails[drillDownId] : null;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between flex-wrap gap-3">
        <h2 className="text-lg font-semibold">일별 실적</h2>
        <DateNavigator
          date={date}
          available={availableDates}
          onChange={setDate}
        />
      </div>

      {settings.emphasizeUnclosed && unclosedCount > 0 && (
        <UnclosedBanner count={unclosedCount} />
      )}

      <SummaryCards summary={summary} />

      <section>
        <div className="flex items-center justify-between mb-3 flex-wrap gap-3">
          <h3 className="text-sm font-semibold text-zinc-400">
            거래 내역 ({dayRows.length}
            {dayRows.length !== allDayRows.length &&
              ` / ${allDayRows.length}`}
            )
          </h3>
          <div className="flex items-center gap-3 flex-wrap">
            <PnLFilterButtons value={pnlFilter} onChange={setPnLFilter} />
            <ReasonFilter
              value={reasonFilter}
              onChange={setReasonFilter}
              available={
                new Set(
                  allDayRows
                    .map((r) => r.closeReason)
                    .filter((x): x is CloseReason => !!x),
                )
              }
            />
            <SortDropdown value={sortKey} onChange={setSortKey} />
          </div>
        </div>

        {dayRows.length === 0 ? (
          <EmptyState
            icon="📊"
            message={
              allDayRows.length === 0
                ? "이 날짜에는 거래가 없습니다"
                : "필터 조건에 맞는 거래가 없습니다"
            }
          />
        ) : (
          <ReportTable
            rows={dayRows}
            emphasizeUnclosed={settings.emphasizeUnclosed}
            onSelect={setDrillDownId}
            selectedId={drillDownId}
          />
        )}
      </section>

      {drillDown && (
        <section>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-zinc-400">
              사이클 상세 — {drillDown.stockName}
            </h3>
            <button
              onClick={() => setDrillDownId(null)}
              className="text-xs text-zinc-500 hover:text-zinc-300"
            >
              닫기 ×
            </button>
          </div>
          <DetailPanel detail={drillDown} live={false} />
        </section>
      )}

      <p className="text-xs text-zinc-500">
        ※ 이전 날짜 조회는 Phase 6에서 활성화됩니다 (백엔드{" "}
        <code>/api/reports/daily?date=</code> 도입 후). 수수료/세금 분리, 매수→매도가
        컬럼도 동시 추가 예정.
      </p>
    </div>
  );
}

// ============================================================
// Date navigator
// ============================================================

function DateNavigator({
  date,
  available,
  onChange,
}: {
  date: string;
  available: string[];
  onChange: (v: string) => void;
}) {
  const idx = available.indexOf(date);
  const canPrev = idx > 0;
  const canNext = idx >= 0 && idx < available.length - 1;
  return (
    <div className="flex items-center gap-2 text-sm">
      <button
        disabled={!canPrev}
        onClick={() => onChange(available[idx - 1])}
        className="px-2 py-1 rounded text-zinc-400 hover:bg-zinc-800 disabled:opacity-40"
      >
        ◀
      </button>
      <span className="px-3 py-1 bg-zinc-900 border border-zinc-800 rounded">
        📅 {date}
      </span>
      <button
        disabled={!canNext}
        onClick={() => onChange(available[idx + 1])}
        className="px-2 py-1 rounded text-zinc-400 hover:bg-zinc-800 disabled:opacity-40"
      >
        ▶
      </button>
      <button
        disabled
        title="오늘 외 날짜 조회는 Phase 6에서 활성화"
        className="px-3 py-1 text-xs rounded text-zinc-500 hover:bg-zinc-800 disabled:cursor-not-allowed"
      >
        오늘
      </button>
    </div>
  );
}

// ============================================================
// UNCLOSED banner
// ============================================================

function UnclosedBanner({ count }: { count: number }) {
  return (
    <div className="bg-rose-950/50 border border-rose-800/60 rounded-lg px-4 py-3 text-sm text-rose-200">
      🚨 UNCLOSED 거래가 {count}건 있습니다 — KIS HTS에서 수동 정리가
      필요합니다 (시스템 다운 또는 거래정지 등으로 자동 마감 실패)
    </div>
  );
}

// ============================================================
// Summary cards
// ============================================================

interface Summary {
  totalProfit: number;
  totalCount: number;
  winCount: number;
  lossCount: number;
  drawCount: number;
  winRate: number;
  reasonCounts: Map<CloseReason, number>;
  avgHoldMs: number;
}

function computeSummary(rows: DailyTrading[]): Summary {
  const totalProfit = rows.reduce((s, r) => s + r.profitAmount, 0);
  const winCount = rows.filter((r) => r.profitAmount > 0).length;
  const lossCount = rows.filter((r) => r.profitAmount < 0).length;
  const drawCount = rows.length - winCount - lossCount;
  const decisive = winCount + lossCount;
  const reasonCounts = new Map<CloseReason, number>();
  for (const r of rows) {
    if (!r.closeReason) continue;
    reasonCounts.set(
      r.closeReason,
      (reasonCounts.get(r.closeReason) ?? 0) + 1,
    );
  }
  const avgHoldMs =
    rows.length === 0
      ? 0
      : rows.reduce((s, r) => {
          if (!r.closedAt) return s;
          return (
            s +
            (new Date(r.closedAt).getTime() -
              new Date(r.createdAt).getTime())
          );
        }, 0) / rows.length;
  return {
    totalProfit,
    totalCount: rows.length,
    winCount,
    lossCount,
    drawCount,
    winRate: decisive === 0 ? 0 : winCount / decisive,
    reasonCounts,
    avgHoldMs,
  };
}

function SummaryCards({ summary }: { summary: Summary }) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
      <SummaryCard title="순수익">
        <ProfitText
          value={summary.totalProfit}
          format={formatKRW}
          className="text-2xl font-bold"
          zeroAsDash
        />
        <p className="text-xs text-zinc-500 mt-2">
          ※ 수수료/세금 분리 표시는 Phase 6에서 활성화
        </p>
      </SummaryCard>

      <SummaryCard title="거래 건수">
        <div className="text-2xl font-bold">{summary.totalCount}건</div>
        <div className="text-sm mt-1 flex gap-3">
          <span className="text-emerald-400">승 {summary.winCount}</span>
          <span className="text-zinc-400">무 {summary.drawCount}</span>
          <span className="text-rose-400">패 {summary.lossCount}</span>
        </div>
        <p className="text-xs text-zinc-500 mt-2">
          승률 {(summary.winRate * 100).toFixed(0)}%
          {summary.totalCount > 0 && (
            <>
              {" · "}평균 보유{" "}
              {formatDuration(
                "1970-01-01T00:00:00",
                new Date(summary.avgHoldMs).toISOString(),
              )}
            </>
          )}
        </p>
      </SummaryCard>

      <SummaryCard title="청산 사유 분포">
        <ReasonDistribution counts={summary.reasonCounts} />
      </SummaryCard>
    </div>
  );
}

function SummaryCard({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3">
        {title}
      </h3>
      {children}
    </div>
  );
}

function ReasonDistribution({
  counts,
}: {
  counts: Map<CloseReason, number>;
}) {
  if (counts.size === 0) {
    return <div className="text-sm text-zinc-500">데이터 없음</div>;
  }
  const total = Array.from(counts.values()).reduce((s, n) => s + n, 0);
  const sorted = Array.from(counts.entries()).sort((a, b) => b[1] - a[1]);
  return (
    <div className="space-y-1.5">
      {sorted.map(([reason, count]) => (
        <div key={reason} className="flex items-center gap-2 text-xs">
          <CloseReasonBadge reason={reason} showLabel={false} />
          <span className="w-8 text-right text-zinc-300">{count}</span>
          <div className="flex-1 bg-zinc-800 rounded-full h-1.5 overflow-hidden">
            <div
              className="h-full bg-zinc-500"
              style={{ width: `${(count / total) * 100}%` }}
            />
          </div>
          <span className="w-10 text-right text-zinc-500">
            {Math.round((count / total) * 100)}%
          </span>
        </div>
      ))}
    </div>
  );
}

// ============================================================
// Filters / sort
// ============================================================

function PnLFilterButtons({
  value,
  onChange,
}: {
  value: PnLFilter;
  onChange: (v: PnLFilter) => void;
}) {
  const opts: { v: PnLFilter; label: string }[] = [
    { v: "all", label: "전체" },
    { v: "win", label: "승만" },
    { v: "loss", label: "패만" },
  ];
  return (
    <div className="flex items-center gap-1 text-xs bg-zinc-900 border border-zinc-800 rounded p-0.5">
      {opts.map((o) => (
        <button
          key={o.v}
          onClick={() => onChange(o.v)}
          className={`px-2 py-1 rounded ${
            value === o.v
              ? "bg-zinc-700 text-zinc-100"
              : "text-zinc-400 hover:text-zinc-200"
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

function ReasonFilter({
  value,
  onChange,
  available,
}: {
  value: Set<CloseReason>;
  onChange: (v: Set<CloseReason>) => void;
  available: Set<CloseReason>;
}) {
  const reasons: CloseReason[] = [
    "TAKE_PROFIT",
    "STOP_LOSS",
    "BREAKEVEN",
    "TREND_BREAK",
    "MARKET_CLOSE",
    "CANCELLED",
    "NO_FILL",
    "UNCLOSED",
  ];
  const visible = reasons.filter((r) => available.has(r));
  if (visible.length === 0) return null;
  return (
    <div className="flex items-center gap-1">
      {visible.map((r) => {
        const selected = value.has(r);
        return (
          <button
            key={r}
            onClick={() => {
              const next = new Set(value);
              if (selected) next.delete(r);
              else next.add(r);
              onChange(next);
            }}
            className={`transition-opacity ${selected ? "opacity-100" : "opacity-40 hover:opacity-70"}`}
            title={selected ? "필터에서 제외" : "필터에 추가"}
          >
            <CloseReasonBadge reason={r} showLabel={false} />
          </button>
        );
      })}
      {value.size > 0 && (
        <button
          onClick={() => onChange(new Set())}
          className="text-xs text-zinc-500 hover:text-zinc-300 ml-1"
        >
          초기화
        </button>
      )}
    </div>
  );
}

function SortDropdown({
  value,
  onChange,
}: {
  value: SortKey;
  onChange: (v: SortKey) => void;
}) {
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className="text-zinc-500">정렬</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value as SortKey)}
        className="bg-zinc-900 border border-zinc-800 rounded px-2 py-1 text-zinc-200"
      >
        <option value="closedAt">종료시각</option>
        <option value="profitRate">수익률</option>
      </select>
    </div>
  );
}

function sortFn(key: SortKey): (a: DailyTrading, b: DailyTrading) => number {
  if (key === "profitRate") return (a, b) => b.profitRate - a.profitRate;
  return (a, b) => (b.closedAt ?? "").localeCompare(a.closedAt ?? "");
}

// ============================================================
// Table
// ============================================================

function ReportTable({
  rows,
  emphasizeUnclosed,
  onSelect,
  selectedId,
}: {
  rows: DailyTrading[];
  emphasizeUnclosed: boolean;
  onSelect: (id: number) => void;
  selectedId: number | null;
}) {
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="bg-zinc-950 text-xs uppercase text-zinc-500">
          <tr>
            <th className="text-left px-4 py-3">종료시각</th>
            <th className="text-left px-4 py-3">종목</th>
            <th className="text-left px-4 py-3">보유시간</th>
            <th className="text-left px-4 py-3">매수→매도가</th>
            <th className="text-right px-4 py-3">수익률</th>
            <th className="text-right px-4 py-3">수익금</th>
            <th className="text-center px-4 py-3">사유</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const isAnomaly =
              emphasizeUnclosed &&
              (r.closeReason === "UNCLOSED" || r.closeReason === "NO_FILL");
            const bg =
              r.closeReason === "UNCLOSED"
                ? "bg-rose-950/30"
                : r.closeReason === "NO_FILL"
                  ? "bg-amber-950/20"
                  : "";
            return (
              <tr
                key={r.commandId}
                onClick={() => onSelect(r.commandId)}
                className={`border-t border-zinc-800 cursor-pointer hover:bg-zinc-800/40 ${
                  selectedId === r.commandId ? "bg-zinc-800/60" : ""
                } ${isAnomaly ? bg : ""}`}
              >
                <td className="px-4 py-3 text-zinc-400 whitespace-nowrap">
                  {r.closedAt ? formatDateTime(r.closedAt) : "-"}
                </td>
                <td className="px-4 py-3 font-medium">
                  {r.stockName}
                  <span className="text-xs text-zinc-500 ml-2">
                    {r.stockCode}
                  </span>
                </td>
                <td className="px-4 py-3 text-zinc-400">
                  {r.closedAt
                    ? formatDuration(r.createdAt, r.closedAt)
                    : "-"}
                </td>
                <td className="px-4 py-3 text-zinc-600 text-xs">
                  — <span className="text-zinc-700">(Phase 6)</span>
                </td>
                <td className="px-4 py-3 text-right">
                  <ProfitText
                    value={r.profitRate}
                    format={formatPct}
                    zeroAsDash
                  />
                </td>
                <td className="px-4 py-3 text-right">
                  <ProfitText
                    value={r.profitAmount}
                    format={formatKRW}
                    zeroAsDash
                  />
                </td>
                <td className="px-4 py-3 text-center">
                  {r.closeReason && (
                    <CloseReasonBadge reason={r.closeReason} />
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
