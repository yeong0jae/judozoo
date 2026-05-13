import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
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
export default function HoldingsPage() {
    const holdingsQ = useHoldings();
    const liquidate = useLiquidateHolding();
    const toast = useToast();
    const [pendingSell, setPendingSell] = useState(null);
    const onConfirmSell = async () => {
        if (!pendingSell)
            return;
        try {
            const result = await liquidate.mutateAsync(pendingSell.stockCode);
            toast.show({
                tone: "success",
                message: `${pendingSell.stockName} ${result.qty}주 매도 발송됨 (ODNO ${result.orderNo})`,
            });
            setPendingSell(null);
        }
        catch (e) {
            const code = e instanceof ApiError ? e.code : "SERVER_ERROR";
            toast.show({ tone: "error", message: `매도 실패 — ${errorMessage(code)}` });
        }
    };
    if (holdingsQ.isLoading) {
        return _jsx("div", { className: "text-sm text-zinc-500", children: "\uB85C\uB529 \uC911..." });
    }
    if (holdingsQ.isError) {
        return _jsx(ErrorState, { onRetry: () => holdingsQ.refetch() });
    }
    const holdings = holdingsQ.data ?? [];
    return (_jsxs("div", { className: "space-y-4", children: [_jsxs("div", { className: "flex items-center justify-between", children: [_jsxs("h2", { className: "text-lg font-semibold", children: ["\uBCF4\uC720 \uC8FC\uC2DD (", holdings.length, ")"] }), _jsx("button", { onClick: () => holdingsQ.refetch(), disabled: holdingsQ.isFetching, className: "px-3 py-1.5 rounded text-xs bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 disabled:opacity-50", children: holdingsQ.isFetching ? "갱신 중..." : "새로 고침" })] }), holdings.length === 0 ? (_jsx(EmptyState, { message: "\uBCF4\uC720 \uC8FC\uC2DD\uC774 \uC5C6\uC2B5\uB2C8\uB2E4" })) : (_jsx("div", { className: "bg-zinc-950 border border-zinc-800 rounded-lg overflow-hidden", children: _jsxs("table", { className: "w-full text-sm", children: [_jsx("thead", { className: "bg-zinc-900 text-zinc-400 text-xs", children: _jsxs("tr", { children: [_jsx("th", { className: "px-4 py-2 text-left", children: "\uC885\uBAA9" }), _jsx("th", { className: "px-4 py-2 text-right", children: "\uBCF4\uC720 \uC218\uB7C9" }), _jsx("th", { className: "px-4 py-2 text-right", children: "\uD3C9\uADE0 \uB9E4\uC785\uAC00" }), _jsx("th", { className: "px-4 py-2 text-right", children: "\uD604\uC7AC\uAC00" }), _jsx("th", { className: "px-4 py-2 text-right", children: "\uD3C9\uAC00\uC190\uC775" }), _jsx("th", { className: "px-4 py-2 text-right", children: "\uC218\uC775\uB960" }), _jsx("th", { className: "px-4 py-2 text-right" })] }) }), _jsx("tbody", { children: holdings.map((h) => (_jsxs("tr", { className: "border-t border-zinc-800", children: [_jsxs("td", { className: "px-4 py-3", children: [_jsx("div", { className: "font-medium", children: h.stockName }), _jsx("div", { className: "text-xs text-zinc-500", children: h.stockCode })] }), _jsx("td", { className: "px-4 py-3 text-right", children: formatQty(h.qty) }), _jsxs("td", { className: "px-4 py-3 text-right text-zinc-300", children: [formatPrice(h.avgBuyPrice), "\uC6D0"] }), _jsxs("td", { className: "px-4 py-3 text-right text-zinc-300", children: [formatPrice(h.currentPrice), "\uC6D0"] }), _jsx("td", { className: "px-4 py-3 text-right", children: _jsx(ProfitText, { value: h.evalProfit, format: formatKRW }) }), _jsx("td", { className: "px-4 py-3 text-right", children: _jsx(ProfitText, { value: h.evalProfitRate, format: formatPct }) }), _jsx("td", { className: "px-4 py-3 text-right whitespace-nowrap", children: h.hasActiveCycle ? (_jsx("span", { className: "inline-flex items-center gap-1 px-2 py-1 rounded text-xs bg-amber-900/30 border border-amber-800/60 text-amber-300", title: "\uD604\uC7AC \uB9E4\uB9E4 \uC911\uC778 \uC885\uBAA9\uC774\uB77C \uC218\uB3D9 \uB9E4\uB3C4\uAC00 \uB9C9\uD600 \uC788\uC74C", children: "\uB9E4\uB9E4 \uC911" })) : (_jsx("button", { onClick: () => setPendingSell(h), className: "px-3 py-1.5 rounded text-xs bg-rose-900/40 hover:bg-rose-900/60 border border-rose-800 text-rose-200 font-medium", children: "\uC2DC\uC7A5\uAC00 \uB9E4\uB3C4" })) })] }, h.stockCode))) })] }) })), pendingSell && (_jsx(ConfirmDialog, { title: "\uC2DC\uC7A5\uAC00 \uB9E4\uB3C4", message: `${pendingSell.stockName} ${pendingSell.qty}주를 시장가로 전량 매도합니다. 진행할까요?`, confirmLoading: liquidate.isPending, onConfirm: onConfirmSell, onCancel: () => setPendingSell(null) }))] }));
}
function ConfirmDialog({ title, message, onConfirm, onCancel, confirmLoading, }) {
    return (_jsx("div", { className: "fixed inset-0 bg-black/70 flex items-center justify-center z-50", children: _jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-6 max-w-md w-full", children: [_jsx("h3", { className: "text-lg font-semibold mb-3", children: title }), _jsx("p", { className: "text-sm text-zinc-300 mb-6", children: message }), _jsxs("div", { className: "flex justify-end gap-2", children: [_jsx("button", { onClick: onCancel, disabled: confirmLoading, className: "px-4 py-2 rounded text-sm bg-zinc-800 hover:bg-zinc-700 disabled:opacity-50", children: "\uB3CC\uC544\uAC00\uAE30" }), _jsx("button", { onClick: onConfirm, disabled: confirmLoading, className: "px-4 py-2 rounded text-sm bg-rose-700 hover:bg-rose-600 text-white font-medium disabled:opacity-50", children: confirmLoading ? "처리 중..." : "매도 진행" })] })] }) }));
}
