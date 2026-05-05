import type {
  ExecutionInfo,
  OrderInfo,
  TradingDetail,
} from "../../types";
import {
  formatDateTime,
  formatKRW,
  formatPct,
  formatPrice,
  formatQty,
  formatTime,
} from "../../lib/format";
import StatusPill from "../common/StatusPill";
import ProfitText from "../common/ProfitText";
import CloseReasonBadge from "../common/CloseReasonBadge";

// 사이클 상세 — 모니터링/실적 양쪽에서 재사용.
// `live: false`는 종료된 사이클 (실적 화면에서 사용) — 취소 버튼 미표시.
export default function DetailPanel({
  detail,
  onCancel,
  live = true,
}: {
  detail: TradingDetail;
  onCancel?: () => void;
  live?: boolean;
}) {
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden">
      <SummaryHeader detail={detail} onCancel={onCancel} live={live} />
      <div className="p-6 space-y-6">
        <ActiveSellAlert orders={detail.orders} />
        <BuyProgressSection detail={detail} />
        <SignalArmingBoard detail={detail} />
        <SplitSellSection detail={detail} />
        <OrderHistory orders={detail.orders} />
        <ExecutionHistory executions={detail.executions} />
      </div>
    </div>
  );
}

// 활성 SELL 주문(아직 미체결) 중 재시도 ≥ 1 있으면 상단 강조.
// retryCount ≥ 3 이면 더 강한 색상.
function ActiveSellAlert({ orders }: { orders: OrderInfo[] }) {
  const activeSell = orders
    .filter(
      (o) => o.side === "SELL" && o.status !== "FILLED" && o.status !== "CANCELLED",
    )
    .sort((a, b) => b.submittedAt.localeCompare(a.submittedAt))[0];
  if (!activeSell || activeSell.retryCount === 0) return null;

  const critical = activeSell.retryCount >= 3;
  const cls = critical
    ? "bg-rose-950/60 border-rose-800 text-rose-200"
    : "bg-amber-950/40 border-amber-800/60 text-amber-200";

  return (
    <div className={`border rounded-md px-4 py-3 text-sm ${cls}`}>
      <div className="flex items-center gap-2 font-medium">
        <span>🔥</span>
        <span>
          매도 재시도 {activeSell.retryCount}회{" "}
          {critical && <span className="text-xs ml-1">(3회 이상 — 점검 필요)</span>}
        </span>
      </div>
      {activeSell.lastError && (
        <div className="text-xs mt-1 text-zinc-300">
          {activeSell.lastError}
        </div>
      )}
      <div className="text-xs mt-1 text-zinc-500">
        트리거 {activeSell.trigger} · 주문 {activeSell.orderQty}주 / 체결{" "}
        {activeSell.filledQty}주
      </div>
    </div>
  );
}

function SummaryHeader({
  detail,
  onCancel,
  live,
}: {
  detail: TradingDetail;
  onCancel?: () => void;
  live: boolean;
}) {
  const isClosed = detail.status === "CLOSED";
  return (
    <div className="bg-zinc-950 px-6 py-5 border-b border-zinc-800">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div className="min-w-0">
          <div className="flex items-center gap-2 mb-2 flex-wrap">
            <StatusPill status={detail.status} />
            {isClosed && detail.closeReason && (
              <CloseReasonBadge reason={detail.closeReason} />
            )}
            <h2 className="text-xl font-semibold">
              {detail.stockName}
              <span className="text-sm text-zinc-500 ml-2">
                {detail.stockCode}
              </span>
            </h2>
          </div>
          <div className="flex gap-x-6 gap-y-1 text-sm text-zinc-400 flex-wrap">
            <span>
              평단{" "}
              <span className="text-zinc-200">
                {formatPrice(detail.averageBuyPrice)}원
              </span>
            </span>
            <span>
              현재{" "}
              <span className="text-zinc-200">
                {formatPrice(detail.currentPrice)}원
              </span>
            </span>
            <span>
              보유{" "}
              <span className="text-zinc-200">
                {formatQty(detail.holdingQty)}
              </span>
            </span>
            <span>
              누적 매수{" "}
              <span className="text-zinc-200">
                {formatQty(detail.totalBoughtQty)}
              </span>
            </span>
          </div>
        </div>
        <div className="flex items-start gap-4">
          <div className="text-right">
            <ProfitText
              value={detail.profitRate}
              format={formatPct}
              className="text-2xl font-bold block"
            />
            <ProfitText
              value={detail.profitAmount}
              format={formatKRW}
              className="text-sm"
            />
          </div>
          {!isClosed && live && onCancel && (
            <button
              onClick={onCancel}
              className="bg-rose-900/40 hover:bg-rose-900/60 border border-rose-800 text-rose-200 px-3 py-1.5 rounded text-sm font-medium transition-colors whitespace-nowrap"
            >
              취소
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

function BuyProgressSection({ detail }: { detail: TradingDetail }) {
  const lastBuy = detail.orders
    .filter((o) => o.side === "BUY")
    .sort((a, b) => b.submittedAt.localeCompare(a.submittedAt))[0];
  const remaining = detail.buyAttempt.total - detail.buyAttempt.completed;
  const nextBuyAt =
    lastBuy && remaining > 0 && detail.status !== "CLOSED"
      ? new Date(
          new Date(lastBuy.submittedAt).getTime() +
            detail.buyIntervalMin * 60_000,
        ).toISOString()
      : null;

  return (
    <Section title="매수 진행">
      <div className="grid grid-cols-3 gap-3">
        {Array.from({ length: detail.buyAttempt.total }).map((_, i) => {
          const completed = i < detail.buyAttempt.completed;
          const isNext =
            i === detail.buyAttempt.completed && nextBuyAt !== null;
          return (
            <div
              key={i}
              className={`border rounded p-3 ${
                completed
                  ? "border-emerald-700/60 bg-emerald-950/30"
                  : isNext
                    ? "border-amber-700/60 bg-amber-950/30"
                    : "border-zinc-800 bg-zinc-950"
              }`}
            >
              <div className="text-xs text-zinc-500 mb-1">회차 {i + 1}</div>
              <div className="text-sm font-medium">
                {completed
                  ? "✓ 체결"
                  : isNext
                    ? "⏱ 다음 매수"
                    : "대기"}
              </div>
              {isNext && nextBuyAt && (
                <div className="text-xs text-amber-300 mt-1">
                  ~{formatTime(nextBuyAt)}
                </div>
              )}
            </div>
          );
        })}
      </div>
      <div className="text-xs text-zinc-500 mt-2">
        1회 매수 {formatKRW(detail.perBuyAmount)} · 간격{" "}
        {detail.buyIntervalMin}분
      </div>
    </Section>
  );
}

function SignalArmingBoard({ detail }: { detail: TradingDetail }) {
  return (
    <Section title="시그널 무장">
      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        <ArmCard label="익절 단계">
          <div className="flex gap-1.5">
            <Stage label="2%" fired={detail.tpStages.fired2pct} />
            <Stage label="3%" fired={detail.tpStages.fired3pct} />
            <Stage label="5%" fired={detail.tpStages.fired5pct} />
          </div>
        </ArmCard>
        <ArmCard label="본전 매도">
          <ArmStatus armed={detail.breakevenArmed} icon="🛡" />
        </ArmCard>
        <ArmCard label="추세 꺾임">
          <ArmStatus armed={detail.trendBreakArmed} icon="📉" />
        </ArmCard>
      </div>
    </Section>
  );
}

function SplitSellSection({ detail }: { detail: TradingDetail }) {
  const pct = detail.splitSellProgress.soldPct;
  const totalSoldQty = detail.executions
    .filter(
      (e) =>
        detail.orders.find((o) => o.id === e.orderId)?.side === "SELL",
    )
    .reduce((sum, e) => sum + e.executedQty, 0);

  return (
    <Section title="분할 매도 진행">
      <div className="flex items-center gap-3">
        <div className="flex-1 bg-zinc-800 rounded-full h-2 overflow-hidden">
          <div
            className="h-full bg-emerald-600 transition-all"
            style={{ width: `${pct}%` }}
          />
        </div>
        <span className="text-sm text-zinc-300 whitespace-nowrap">
          {pct}% ({formatQty(totalSoldQty)} /{" "}
          {formatQty(detail.totalBoughtQty)})
        </span>
      </div>
      <div className="text-xs text-zinc-500 mt-2">
        분할 비율 {(detail.splitSellRatio * 100).toFixed(0)}% / 회 · 중도 익절
        +{detail.midwayProfitPct}% · 본전 +{detail.breakevenThresholdPct}% ·
        손절 -{detail.stopLossPct}%
      </div>
    </Section>
  );
}

function OrderHistory({ orders }: { orders: OrderInfo[] }) {
  if (orders.length === 0) return null;
  return (
    <Section title={`주문 이력 (${orders.length})`}>
      <div className="overflow-x-auto -mx-2">
        <table className="w-full text-xs">
          <thead className="text-zinc-500 uppercase">
            <tr>
              <th className="text-left px-2 py-2 font-medium">제출시각</th>
              <th className="text-left px-2 py-2 font-medium">방향</th>
              <th className="text-left px-2 py-2 font-medium">트리거</th>
              <th className="text-right px-2 py-2 font-medium">주문</th>
              <th className="text-right px-2 py-2 font-medium">체결</th>
              <th className="text-left px-2 py-2 font-medium">상태</th>
              <th className="text-right px-2 py-2 font-medium">재시도</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((o) => {
              const highlight =
                o.side === "SELL" &&
                o.status !== "FILLED" &&
                o.status !== "CANCELLED" &&
                o.retryCount >= 3;
              return (
              <tr
                key={o.id}
                className={`border-t border-zinc-800 ${highlight ? "bg-rose-950/30" : ""}`}
              >
                <td className="px-2 py-2 text-zinc-400 whitespace-nowrap">
                  {formatDateTime(o.submittedAt)}
                </td>
                <td className="px-2 py-2">
                  <SideBadge side={o.side} />
                </td>
                <td className="px-2 py-2 text-zinc-400">{o.trigger}</td>
                <td className="px-2 py-2 text-right">{o.orderQty}</td>
                <td className="px-2 py-2 text-right">{o.filledQty}</td>
                <td className="px-2 py-2">
                  <OrderStatusText status={o.status} />
                </td>
                <td className="px-2 py-2 text-right">
                  {o.retryCount > 0 ? (
                    <span
                      className={
                        o.retryCount >= 3
                          ? "text-rose-300 font-medium"
                          : "text-amber-300"
                      }
                      title={o.lastError ?? undefined}
                    >
                      {o.retryCount}회
                    </span>
                  ) : (
                    <span className="text-zinc-600">-</span>
                  )}
                </td>
              </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </Section>
  );
}

function ExecutionHistory({ executions }: { executions: ExecutionInfo[] }) {
  if (executions.length === 0) return null;
  return (
    <Section title={`체결 이력 (${executions.length})`}>
      <div className="overflow-x-auto -mx-2">
        <table className="w-full text-xs">
          <thead className="text-zinc-500 uppercase">
            <tr>
              <th className="text-left px-2 py-2 font-medium">체결시각</th>
              <th className="text-right px-2 py-2 font-medium">수량</th>
              <th className="text-right px-2 py-2 font-medium">가격</th>
              <th className="text-right px-2 py-2 font-medium">수수료</th>
              <th className="text-right px-2 py-2 font-medium">세금</th>
            </tr>
          </thead>
          <tbody>
            {executions.map((e, i) => (
              <tr
                key={`${e.orderId}-${i}`}
                className="border-t border-zinc-800"
              >
                <td className="px-2 py-2 text-zinc-400 whitespace-nowrap">
                  {formatDateTime(e.executedAt)}
                </td>
                <td className="px-2 py-2 text-right">{e.executedQty}</td>
                <td className="px-2 py-2 text-right">
                  {formatPrice(e.executedPrice)}
                </td>
                <td className="px-2 py-2 text-right text-zinc-500">
                  {e.fee === 0 ? "-" : formatKRW(e.fee)}
                </td>
                <td className="px-2 py-2 text-right text-zinc-500">
                  {e.tax === 0 ? "-" : formatKRW(e.tax)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Section>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <h3 className="text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3">
        {title}
      </h3>
      {children}
    </div>
  );
}

function ArmCard({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="border border-zinc-800 bg-zinc-950 rounded p-3">
      <div className="text-xs text-zinc-500 mb-2">{label}</div>
      <div>{children}</div>
    </div>
  );
}

function ArmStatus({ armed, icon }: { armed: boolean; icon: string }) {
  return (
    <span className={armed ? "text-amber-300 font-medium" : "text-zinc-500"}>
      {armed ? `${icon} 무장됨` : "⚪ 미무장"}
    </span>
  );
}

function Stage({ label, fired }: { label: string; fired: boolean }) {
  return (
    <span
      className={`px-2 py-0.5 rounded text-xs border ${
        fired
          ? "bg-emerald-900/60 text-emerald-300 border-emerald-800"
          : "bg-zinc-800 text-zinc-500 border-zinc-700"
      }`}
    >
      {label} {fired && "✓"}
    </span>
  );
}

function SideBadge({ side }: { side: "BUY" | "SELL" }) {
  return (
    <span
      className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${
        side === "BUY"
          ? "bg-blue-900/60 text-blue-300"
          : "bg-rose-900/60 text-rose-300"
      }`}
    >
      {side}
    </span>
  );
}

function OrderStatusText({ status }: { status: string }) {
  const cls =
    status === "FILLED"
      ? "text-emerald-300"
      : status === "CANCELLED"
        ? "text-zinc-500"
        : "text-amber-300";
  return <span className={cls}>{status}</span>;
}
