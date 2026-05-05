import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  mockActiveCommands,
  mockCommandDetails,
  mockTodayClosed,
} from "../mocks/data";
import type {
  ExecutionInfo,
  OrderInfo,
  TradingDetail,
  TradingSummary,
} from "../types";
import {
  colorByPnL,
  formatDateTime,
  formatKRW,
  formatPct,
  formatPrice,
  formatQty,
  formatTime,
} from "../lib/format";
import StatusPill from "../components/common/StatusPill";
import ProfitText from "../components/common/ProfitText";
import EmptyState from "../components/common/EmptyState";
import CloseReasonBadgeCommon from "../components/common/CloseReasonBadge";

export default function MonitoringPage() {
  const [selectedId, setSelectedId] = useState<number | null>(
    mockActiveCommands[0]?.commandId ?? null,
  );
  const [showCancelDialog, setShowCancelDialog] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  // 종료 토스트 데모: 페이지 진입 후 4초 뒤 NO_FILL 종료 토스트 시뮬레이션
  useEffect(() => {
    const t = setTimeout(() => {
      setToast("LG에너지솔루션 명령 종료 — NO_FILL");
      setTimeout(() => setToast(null), 4000);
    }, 4000);
    return () => clearTimeout(t);
  }, []);

  const selectedDetail = selectedId ? mockCommandDetails[selectedId] : null;

  return (
    <div className="space-y-6">
      <section>
        <h2 className="text-lg font-semibold mb-3">
          활성 명령 ({mockActiveCommands.length})
        </h2>
        {mockActiveCommands.length === 0 ? (
          <EmptyState
            message="활성 매매 명령이 없습니다"
            action={
              <Link
                to="/command"
                className="px-4 py-2 rounded bg-emerald-700 hover:bg-emerald-600 text-sm text-white"
              >
                매매 명령 작성하기 →
              </Link>
            }
          />
        ) : (
          <div className="space-y-2">
            {mockActiveCommands.map((cmd) => (
              <ActiveRow
                key={cmd.commandId}
                cmd={cmd}
                selected={selectedId === cmd.commandId}
                onSelect={() => setSelectedId(cmd.commandId)}
              />
            ))}
          </div>
        )}
      </section>

      {selectedDetail && (
        <section>
          <h2 className="text-lg font-semibold mb-3">상세 — {selectedDetail.stockName}</h2>
          <DetailPanel detail={selectedDetail} onCancel={() => setShowCancelDialog(true)} />
        </section>
      )}

      <section>
        <h3 className="text-sm font-semibold text-zinc-400 mb-3">오늘 종료된 명령</h3>
        {mockTodayClosed.length === 0 ? (
          <p className="text-sm text-zinc-600">없음</p>
        ) : (
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-zinc-950 text-xs uppercase text-zinc-500">
                <tr>
                  <th className="text-left px-4 py-2">종목명</th>
                  <th className="text-left px-4 py-2">청산 사유</th>
                  <th className="text-right px-4 py-2">수익률</th>
                </tr>
              </thead>
              <tbody>
                {mockTodayClosed.map((row) => (
                  <tr key={row.commandId} className="border-t border-zinc-800">
                    <td className="px-4 py-2">{row.stockName}</td>
                    <td className="px-4 py-2">
                      {row.closeReason && <CloseReasonBadge reason={row.closeReason} />}
                    </td>
                    <td className={`px-4 py-2 text-right ${colorByPnL(row.profitRate)}`}>
                      {row.profitRate === 0 ? "-" : formatPct(row.profitRate)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {toast && (
        <div className="fixed bottom-6 right-6 bg-zinc-800 border border-zinc-700 px-4 py-3 rounded-lg shadow-lg text-sm text-zinc-200">
          {toast}
        </div>
      )}

      {showCancelDialog && selectedDetail && (
        <ConfirmDialog
          title="매매 사이클 취소"
          message={`${selectedDetail.stockName} 명령을 즉시 청산합니다. 보유 ${formatQty(selectedDetail.holdingQty)}이 시장가로 매도됩니다. 계속할까요?`}
          onConfirm={() => setShowCancelDialog(false)}
          onCancel={() => setShowCancelDialog(false)}
        />
      )}
    </div>
  );
}

function DetailPanel({
  detail,
  onCancel,
  live = true,
}: {
  detail: TradingDetail;
  onCancel: () => void;
  live?: boolean;
}) {
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden">
      <SummaryHeader detail={detail} onCancel={onCancel} live={live} />
      <div className="p-6 space-y-6">
        <BuyProgressSection detail={detail} />
        <SignalArmingBoard detail={detail} />
        <SplitSellSection detail={detail} />
        <OrderHistory orders={detail.orders} />
        <ExecutionHistory executions={detail.executions} />
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
  onCancel: () => void;
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
              <CloseReasonBadgeCommon reason={detail.closeReason} />
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
          {!isClosed && live && (
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
  const remaining =
    detail.buyAttempt.total - detail.buyAttempt.completed;
  const nextBuyAt =
    lastBuy && remaining > 0
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
          const isNext = i === detail.buyAttempt.completed && nextBuyAt !== null;
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
                {completed ? "✓ 체결" : isNext ? "⏱ 다음 매수" : "대기"}
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
          {pct}% ({formatQty(totalSoldQty)} / {formatQty(detail.totalBoughtQty)})
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
            {orders.map((o) => (
              <tr key={o.id} className="border-t border-zinc-800">
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
            ))}
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
              <tr key={`${e.orderId}-${i}`} className="border-t border-zinc-800">
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
    <span
      className={
        armed ? "text-amber-300 font-medium" : "text-zinc-500"
      }
    >
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

function ActiveRow({
  cmd,
  selected,
  onSelect,
}: {
  cmd: TradingSummary;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      onClick={onSelect}
      className={`w-full text-left rounded-lg border p-4 transition-colors ${
        selected
          ? "border-emerald-700 bg-zinc-800/60"
          : "border-zinc-800 bg-zinc-900 hover:bg-zinc-800/40"
      }`}
    >
      <div className="grid grid-cols-[auto_1fr_auto_auto] items-center gap-4">
        <StatusPill status={cmd.status} />
        <div className="min-w-0">
          <div className="font-medium truncate">
            {cmd.stockName}
            <span className="text-xs text-zinc-500 ml-2">{cmd.stockCode}</span>
          </div>
          <div className="text-xs text-zinc-500 mt-0.5">
            평단 {formatPrice(cmd.averageBuyPrice)} → 현재{" "}
            {formatPrice(cmd.currentPrice)}
          </div>
        </div>
        <div className="text-right">
          <ProfitText
            value={cmd.profitRate}
            format={formatPct}
            className="text-lg font-semibold"
          />
          <div className="text-xs">
            <ProfitText value={cmd.profitAmount} format={formatKRW} />
          </div>
        </div>
        <div className="flex flex-col items-end gap-1.5 min-w-[70px]">
          <BuyAttemptDots
            completed={cmd.buyAttempt.completed}
            total={cmd.buyAttempt.total}
          />
          <div className="text-xs text-zinc-400 flex items-center gap-1">
            {formatQty(cmd.holdingQty)}
            {cmd.status === "LIQUIDATING" && (
              <span title="청산 중" aria-label="청산 중">🔥</span>
            )}
          </div>
        </div>
      </div>
    </button>
  );
}

function BuyAttemptDots({
  completed,
  total,
}: {
  completed: number;
  total: number;
}) {
  return (
    <div
      className="flex gap-1"
      title={`매수 ${completed}/${total} 회차 완료`}
      aria-label={`매수 ${completed}/${total} 회차`}
    >
      {Array.from({ length: total }).map((_, i) => (
        <span
          key={i}
          className={`w-2 h-2 rounded-full ${
            i < completed ? "bg-emerald-400" : "bg-zinc-700"
          }`}
        />
      ))}
    </div>
  );
}

function CloseReasonBadge({ reason }: { reason: string }) {
  const danger = ["STOP_LOSS", "UNCLOSED", "NO_FILL", "MARKET_CLOSE"].includes(
    reason,
  );
  const success = ["TAKE_PROFIT", "TREND_BREAK"].includes(reason);
  const cls = danger
    ? "bg-rose-900/40 text-rose-300 border-rose-800"
    : success
      ? "bg-emerald-900/40 text-emerald-300 border-emerald-800"
      : "bg-zinc-800 text-zinc-300 border-zinc-700";
  return (
    <span className={`px-2 py-0.5 rounded text-xs border ${cls}`}>
      {reason}
    </span>
  );
}

function ConfirmDialog({
  title,
  message,
  onConfirm,
  onCancel,
}: {
  title: string;
  message: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50">
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-6 max-w-md w-full">
        <h3 className="text-lg font-semibold mb-3">{title}</h3>
        <p className="text-sm text-zinc-300 mb-6">{message}</p>
        <div className="flex justify-end gap-2">
          <button
            onClick={onCancel}
            className="px-4 py-2 rounded text-sm bg-zinc-800 hover:bg-zinc-700"
          >
            돌아가기
          </button>
          <button
            onClick={onConfirm}
            className="px-4 py-2 rounded text-sm bg-rose-700 hover:bg-rose-600 text-white font-medium"
          >
            취소 진행
          </button>
        </div>
      </div>
    </div>
  );
}
