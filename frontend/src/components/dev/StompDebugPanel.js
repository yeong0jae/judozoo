import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
// Dev-only: STOMP / 인지 채널 시뮬레이터.
// Phase 5-B-2 wire 후에도 mock 발화 시나리오 재현용으로 유지 (실 STOMP 연결과 분리).
import { useState } from "react";
import { useStompState } from "../../ws/stompMock";
import { useNotifications } from "../../notifications/notifications";
import { useToast } from "../toast/Toast";
const SAMPLE_STOCKS = [
    { code: "005930", name: "삼성전자" },
    { code: "000660", name: "SK하이닉스" },
    { code: "035720", name: "카카오" },
];
const REASONS = [
    "TAKE_PROFIT",
    "STOP_LOSS",
    "BREAKEVEN",
    "TREND_BREAK",
    "MARKET_CLOSE",
    "CANCELLED",
    "NO_FILL",
    "UNCLOSED",
];
export default function StompDebugPanel() {
    const [open, setOpen] = useState(false);
    const stomp = useStompState();
    const notifications = useNotifications();
    const toast = useToast();
    const fireClose = (reason) => {
        const stock = SAMPLE_STOCKS[Math.floor(Math.random() * SAMPLE_STOCKS.length)];
        const commandId = Math.floor(Math.random() * 100_000);
        const ts = new Date().toISOString();
        notifications.add({
            ts,
            commandId,
            closeReason: reason,
            stockName: stock.name,
            stockCode: stock.code,
        });
        toast.show({
            message: `${stock.name} 명령이 종료되었습니다`,
            closeReason: reason,
        });
    };
    const fireBalanceInvalidated = () => {
        toast.show({
            tone: "info",
            message: "잔고 변동 감지 — 새로고침 트리거 (mock)",
        });
    };
    return (_jsx("div", { className: "fixed bottom-6 left-6 z-40", children: !open ? (_jsx("button", { onClick: () => setOpen(true), className: "text-xs bg-zinc-900 border border-zinc-700 text-zinc-400 hover:text-zinc-100 px-3 py-1.5 rounded shadow", title: "\uAC1C\uBC1C \uBAA8\uB4DC STOMP \uC2DC\uBBAC\uB808\uC774\uD130", children: "\uD83D\uDEE0 dev" })) : (_jsxs("div", { className: "bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl p-3 w-72 text-xs", children: [_jsxs("div", { className: "flex items-center justify-between mb-3", children: [_jsx("span", { className: "font-semibold text-zinc-300", children: "\uD83D\uDEE0 STOMP \uC2DC\uBBAC\uB808\uC774\uD130" }), _jsx("button", { onClick: () => setOpen(false), className: "text-zinc-500 hover:text-zinc-200", children: "\u00D7" })] }), _jsx(Group, { label: "STOMP \uC5F0\uACB0", children: ["connected", "reconnecting", "disconnected"].map((s) => (_jsx("button", { onClick: () => stomp.setState(s), className: `px-2 py-1 rounded ${stomp.state === s
                            ? "bg-zinc-700 text-zinc-100"
                            : "bg-zinc-800 text-zinc-400 hover:text-zinc-200"}`, children: s }, s))) }), _jsx(Group, { label: "lifecycle CLOSED", children: REASONS.map((r) => (_jsx("button", { onClick: () => fireClose(r), className: "px-1.5 py-1 rounded bg-zinc-800 text-zinc-400 hover:text-zinc-200", title: `${r} 종료 발화`, children: r.split("_")[0] }, r))) }), _jsx(Group, { label: "account", children: _jsx("button", { onClick: fireBalanceInvalidated, className: "px-2 py-1 rounded bg-zinc-800 text-zinc-400 hover:text-zinc-200", children: "BALANCE_INVALIDATED" }) }), _jsx("p", { className: "mt-3 text-[10px] text-zinc-600 leading-relaxed", children: "Phase 5-B-2\uC5D0\uC11C \uC2E4 STOMP \uC5F0\uACB0\uB85C \uAD50\uCCB4. \uBCF8 \uD328\uB110\uC740 mock \uC2DC\uB098\uB9AC\uC624 \uC7AC\uD604\uC6A9\uC73C\uB85C \uC720\uC9C0." })] })) }));
}
function Group({ label, children, }) {
    return (_jsxs("div", { className: "mb-3 last:mb-0", children: [_jsx("div", { className: "text-[10px] text-zinc-500 uppercase tracking-wider mb-1.5", children: label }), _jsx("div", { className: "flex flex-wrap gap-1", children: children })] }));
}
