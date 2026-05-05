import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
// Dev-only: 인지 채널 시뮬레이터 + STOMP 강제 끊기/복구.
// 실 STOMP 연결 상태는 백엔드 기동/종료에 따라 자동으로 변하지만,
// 끊김 시나리오를 빠르게 재현하기 위해 deactivate/activate를 수동 트리거할 수 있게 둔다.
import { useState } from "react";
import { useStompState } from "../../ws/StompProvider";
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
    const { client, state } = useStompState();
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
    const forceReconnect = async () => {
        if (client.active) {
            await client.deactivate();
        }
        client.activate();
    };
    return (_jsx("div", { className: "fixed bottom-6 left-6 z-40", children: !open ? (_jsx("button", { onClick: () => setOpen(true), className: "text-xs bg-zinc-900 border border-zinc-700 text-zinc-400 hover:text-zinc-100 px-3 py-1.5 rounded shadow", title: "\uAC1C\uBC1C \uBAA8\uB4DC \uC2DC\uBBAC\uB808\uC774\uD130", children: "\uD83D\uDEE0 dev" })) : (_jsxs("div", { className: "bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl p-3 w-72 text-xs", children: [_jsxs("div", { className: "flex items-center justify-between mb-3", children: [_jsx("span", { className: "font-semibold text-zinc-300", children: "\uD83D\uDEE0 \uC2DC\uBBAC\uB808\uC774\uD130" }), _jsx("button", { onClick: () => setOpen(false), className: "text-zinc-500 hover:text-zinc-200", children: "\u00D7" })] }), _jsx(Group, { label: `STOMP (현재 ${state})`, children: _jsx("button", { onClick: forceReconnect, className: "px-2 py-1 rounded bg-zinc-800 text-zinc-400 hover:text-zinc-200", children: "\uAC15\uC81C \uC7AC\uC5F0\uACB0" }) }), _jsx(Group, { label: "lifecycle CLOSED (mock \uBC1C\uD654)", children: REASONS.map((r) => (_jsx("button", { onClick: () => fireClose(r), className: "px-1.5 py-1 rounded bg-zinc-800 text-zinc-400 hover:text-zinc-200", title: `${r} 종료 발화`, children: r.split("_")[0] }, r))) }), _jsx(Group, { label: "account", children: _jsx("button", { onClick: fireBalanceInvalidated, className: "px-2 py-1 rounded bg-zinc-800 text-zinc-400 hover:text-zinc-200", children: "BALANCE_INVALIDATED" }) }), _jsx("p", { className: "mt-3 text-[10px] text-zinc-600 leading-relaxed", children: "\uC2E4 STOMP\uB294 \uC790\uB3D9\uC73C\uB85C \uC7AC\uC5F0\uACB0\uB429\uB2C8\uB2E4. \uBC1C\uD654 \uBC84\uD2BC\uC740 mock \uD1A0\uC2A4\uD2B8/\uC54C\uB9BC\uC744 \uD2B8\uB9AC\uAC70\uD569\uB2C8\uB2E4." })] })) }));
}
function Group({ label, children, }) {
    return (_jsxs("div", { className: "mb-3 last:mb-0", children: [_jsx("div", { className: "text-[10px] text-zinc-500 uppercase tracking-wider mb-1.5", children: label }), _jsx("div", { className: "flex flex-wrap gap-1", children: children })] }));
}
