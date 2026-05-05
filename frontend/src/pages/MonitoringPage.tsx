import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  mockActiveCommands,
  mockCommandDetails,
  mockTodayClosed,
} from "../mocks/data";
import type { TradingDetail, TradingSummary } from "../types";
import { colorByPnL, formatKRW, formatPct, formatPrice, formatQty } from "../lib/format";
import StatusPill from "../components/common/StatusPill";
import ProfitText from "../components/common/ProfitText";
import EmptyState from "../components/common/EmptyState";

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
}: {
  detail: TradingDetail;
  onCancel: () => void;
}) {
  const showSignals = detail.status === "HOLDING";
  const showMidwayOnly = detail.status === "BUYING";

  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-6 grid grid-cols-1 md:grid-cols-3 gap-6">
      <div className="space-y-3">
        <Section title="포지션">
          <KV label="평균 매수가" value={`${formatPrice(detail.averageBuyPrice)}원`} />
          <KV label="현재가" value={`${formatPrice(detail.currentPrice)}원`} />
          <KV
            label="수익률"
            value={formatPct(detail.profitRate)}
            valueClass={colorByPnL(detail.profitRate)}
          />
          <KV
            label="평가손익"
            value={formatKRW(detail.profitAmount)}
            valueClass={colorByPnL(detail.profitAmount)}
          />
          <KV label="보유 수량" value={formatQty(detail.holdingQty)} />
          <KV label="누적 매수" value={formatQty(detail.totalBoughtQty)} />
        </Section>
      </div>

      <div className="space-y-3">
        <Section title="매수 진행">
          <KV
            label="회차 진행도"
            value={`${detail.buyAttempt.completed} / ${detail.buyAttempt.total}`}
          />
          <KV label="1회 매수 금액" value={formatKRW(detail.perBuyAmount)} />
          <KV label="추가 매수 간격" value={`${detail.buyIntervalMin}분`} />
        </Section>

        <Section title="시그널" muted={!showSignals && !showMidwayOnly}>
          {showMidwayOnly && (
            <p className="text-xs text-zinc-500">
              매수 진행 중 — 중도 익절 외 시그널은 비활성
            </p>
          )}
          <KV
            label="목표 익절 단계"
            value=""
            extra={
              <div className="flex gap-1.5">
                <Stage label="2%" fired={detail.tpStages.fired2pct} active={showSignals} />
                <Stage label="3%" fired={detail.tpStages.fired3pct} active={showSignals} />
                <Stage label="5%" fired={detail.tpStages.fired5pct} active={showSignals} />
              </div>
            }
          />
          <KV
            label="분할 매도 진행"
            value={`${detail.splitSellProgress.soldPct}% / 100%`}
          />
          <KV
            label="본전 매도 무장"
            value={detail.breakevenArmed ? "무장됨" : "대기"}
            valueClass={detail.breakevenArmed ? "text-amber-400" : "text-zinc-500"}
          />
          <KV
            label="추세 꺾임 무장"
            value={detail.trendBreakArmed ? "무장됨" : "대기"}
            valueClass={detail.trendBreakArmed ? "text-amber-400" : "text-zinc-500"}
          />
        </Section>
      </div>

      <div className="space-y-3">
        <Section title="설정값">
          <KV label="분할 매도 비율" value={`${(detail.splitSellRatio * 100).toFixed(0)}%`} />
          <KV label="중도 익절" value={`+${detail.midwayProfitPct}%`} />
          <KV label="본전 매도" value={`+${detail.breakevenThresholdPct}%`} />
          <KV label="손절" value={`${detail.stopLossPct}%`} />
        </Section>

        <button
          onClick={onCancel}
          className="w-full mt-2 bg-rose-900/40 hover:bg-rose-900/60 border border-rose-800 text-rose-200 px-4 py-2 rounded-md text-sm font-medium transition-colors"
        >
          매매 사이클 취소
        </button>
      </div>
    </div>
  );
}

function Section({
  title,
  children,
  muted,
  tone,
}: {
  title: string;
  children: React.ReactNode;
  muted?: boolean;
  tone?: "danger";
}) {
  const cls = muted
    ? "opacity-50"
    : tone === "danger"
      ? "border-rose-900/60 bg-rose-950/30"
      : "border-zinc-800 bg-zinc-950";
  return (
    <div className={`border ${cls} rounded-md p-4`}>
      <div className="text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-2">
        {title}
      </div>
      <div className="space-y-1.5">{children}</div>
    </div>
  );
}

function KV({
  label,
  value,
  valueClass,
  extra,
  small,
}: {
  label: string;
  value: string;
  valueClass?: string;
  extra?: React.ReactNode;
  small?: boolean;
}) {
  return (
    <div className="flex justify-between items-center text-sm">
      <span className="text-zinc-500">{label}</span>
      {extra ?? (
        <span
          className={`${valueClass ?? "text-zinc-100"} ${small ? "text-xs" : ""}`}
        >
          {value}
        </span>
      )}
    </div>
  );
}

function Stage({
  label,
  fired,
  active,
}: {
  label: string;
  fired: boolean;
  active: boolean;
}) {
  if (!active) {
    return (
      <span className="px-2 py-0.5 rounded text-xs bg-zinc-800 text-zinc-600">
        {label}
      </span>
    );
  }
  return (
    <span
      className={`px-2 py-0.5 rounded text-xs ${
        fired
          ? "bg-emerald-900/60 text-emerald-300 border border-emerald-800"
          : "bg-zinc-800 text-zinc-400 border border-zinc-700"
      }`}
    >
      {label} {fired && "✓"}
    </span>
  );
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
