import { useState } from "react";
import EmptyState from "../components/common/EmptyState";
import ErrorState from "../components/common/ErrorState";
import ProfitText from "../components/common/ProfitText";
import { ApiError } from "../api/client";
import { useHoldings } from "../api/queries";
import { useLiquidateHolding } from "../api/mutations";
import { useToast } from "../components/toast/Toast";
import { errorMessage } from "../lib/errorMessages";
import { formatKRW, formatPct, formatPrice, formatQty } from "../lib/format";
import type { Holding } from "../types";

export default function HoldingsPage() {
  const holdingsQ = useHoldings();
  const liquidate = useLiquidateHolding();
  const toast = useToast();
  const [pendingSell, setPendingSell] = useState<Holding | null>(null);

  const onConfirmSell = async () => {
    if (!pendingSell) return;
    try {
      const result = await liquidate.mutateAsync(pendingSell.stockCode);
      toast.show({
        tone: "success",
        message: `${pendingSell.stockName} ${result.qty}주 매도 발송됨 (ODNO ${result.orderNo})`,
      });
      setPendingSell(null);
    } catch (e) {
      const code = e instanceof ApiError ? e.code : "SERVER_ERROR";
      toast.show({ tone: "error", message: `매도 실패 — ${errorMessage(code)}` });
    }
  };

  if (holdingsQ.isLoading) {
    return <div className="text-sm text-zinc-500">로딩 중...</div>;
  }
  if (holdingsQ.isError) {
    return <ErrorState onRetry={() => holdingsQ.refetch()} />;
  }
  const holdings = holdingsQ.data ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">보유 주식 ({holdings.length})</h2>
        <button
          onClick={() => holdingsQ.refetch()}
          disabled={holdingsQ.isFetching}
          className="px-3 py-1.5 rounded text-xs bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 disabled:opacity-50"
        >
          {holdingsQ.isFetching ? "갱신 중..." : "새로 고침"}
        </button>
      </div>

      {holdings.length === 0 ? (
        <EmptyState message="보유 주식이 없습니다" />
      ) : (
        <div className="bg-zinc-950 border border-zinc-800 rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-zinc-900 text-zinc-400 text-xs">
              <tr>
                <th className="px-4 py-2 text-left">종목</th>
                <th className="px-4 py-2 text-right">보유 수량</th>
                <th className="px-4 py-2 text-right">평균 매입가</th>
                <th className="px-4 py-2 text-right">현재가</th>
                <th className="px-4 py-2 text-right">평가손익</th>
                <th className="px-4 py-2 text-right">수익률</th>
                <th className="px-4 py-2 text-right" />
              </tr>
            </thead>
            <tbody>
              {holdings.map((h) => (
                <tr key={h.stockCode} className="border-t border-zinc-800">
                  <td className="px-4 py-3">
                    <div className="font-medium">{h.stockName}</div>
                    <div className="text-xs text-zinc-500">{h.stockCode}</div>
                  </td>
                  <td className="px-4 py-3 text-right">{formatQty(h.qty)}</td>
                  <td className="px-4 py-3 text-right text-zinc-300">
                    {formatPrice(h.avgBuyPrice)}원
                  </td>
                  <td className="px-4 py-3 text-right text-zinc-300">
                    {formatPrice(h.currentPrice)}원
                  </td>
                  <td className="px-4 py-3 text-right">
                    <ProfitText value={h.evalProfit} format={formatKRW} />
                  </td>
                  <td className="px-4 py-3 text-right">
                    <ProfitText value={h.evalProfitRate} format={formatPct} />
                  </td>
                  <td className="px-4 py-3 text-right whitespace-nowrap">
                    {h.hasActiveCycle ? (
                      <span
                        className="inline-flex items-center gap-1 px-2 py-1 rounded text-xs bg-amber-900/30 border border-amber-800/60 text-amber-300"
                        title="현재 매매 중인 종목이라 수동 매도가 막혀 있음"
                      >
                        매매 중
                      </span>
                    ) : (
                      <button
                        onClick={() => setPendingSell(h)}
                        className="px-3 py-1.5 rounded text-xs bg-rose-900/40 hover:bg-rose-900/60 border border-rose-800 text-rose-200 font-medium"
                      >
                        시장가 매도
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {pendingSell && (
        <ConfirmDialog
          title="시장가 매도"
          message={`${pendingSell.stockName} ${pendingSell.qty}주를 시장가로 전량 매도합니다. 진행할까요?`}
          confirmLoading={liquidate.isPending}
          onConfirm={onConfirmSell}
          onCancel={() => setPendingSell(null)}
        />
      )}
    </div>
  );
}

function ConfirmDialog({
  title,
  message,
  onConfirm,
  onCancel,
  confirmLoading,
}: {
  title: string;
  message: string;
  onConfirm: () => void;
  onCancel: () => void;
  confirmLoading?: boolean;
}) {
  return (
    <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50">
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-6 max-w-md w-full">
        <h3 className="text-lg font-semibold mb-3">{title}</h3>
        <p className="text-sm text-zinc-300 mb-6">{message}</p>
        <div className="flex justify-end gap-2">
          <button
            onClick={onCancel}
            disabled={confirmLoading}
            className="px-4 py-2 rounded text-sm bg-zinc-800 hover:bg-zinc-700 disabled:opacity-50"
          >
            돌아가기
          </button>
          <button
            onClick={onConfirm}
            disabled={confirmLoading}
            className="px-4 py-2 rounded text-sm bg-rose-700 hover:bg-rose-600 text-white font-medium disabled:opacity-50"
          >
            {confirmLoading ? "처리 중..." : "매도 진행"}
          </button>
        </div>
      </div>
    </div>
  );
}
