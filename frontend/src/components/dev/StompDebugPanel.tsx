// Dev-only: 인지 채널 시뮬레이터 + STOMP 강제 끊기/복구.
// 실 STOMP 연결 상태는 백엔드 기동/종료에 따라 자동으로 변하지만,
// 끊김 시나리오를 빠르게 재현하기 위해 deactivate/activate를 수동 트리거할 수 있게 둔다.

import { useState } from "react";
import type { CloseReason } from "../../types";
import { useStompState } from "../../ws/StompProvider";
import { useNotifications } from "../../notifications/notifications";
import { useToast } from "../toast/Toast";

const SAMPLE_STOCKS = [
  { code: "005930", name: "삼성전자" },
  { code: "000660", name: "SK하이닉스" },
  { code: "035720", name: "카카오" },
];

const REASONS: CloseReason[] = [
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

  const fireClose = (reason: CloseReason) => {
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

  return (
    <div className="fixed bottom-6 left-6 z-40">
      {!open ? (
        <button
          onClick={() => setOpen(true)}
          className="text-xs bg-zinc-900 border border-zinc-700 text-zinc-400 hover:text-zinc-100 px-3 py-1.5 rounded shadow"
          title="개발 모드 시뮬레이터"
        >
          🛠 dev
        </button>
      ) : (
        <div className="bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl p-3 w-72 text-xs">
          <div className="flex items-center justify-between mb-3">
            <span className="font-semibold text-zinc-300">🛠 시뮬레이터</span>
            <button
              onClick={() => setOpen(false)}
              className="text-zinc-500 hover:text-zinc-200"
            >
              ×
            </button>
          </div>

          <Group label={`STOMP (현재 ${state})`}>
            <button
              onClick={forceReconnect}
              className="px-2 py-1 rounded bg-zinc-800 text-zinc-400 hover:text-zinc-200"
            >
              강제 재연결
            </button>
          </Group>

          <Group label="lifecycle CLOSED (mock 발화)">
            {REASONS.map((r) => (
              <button
                key={r}
                onClick={() => fireClose(r)}
                className="px-1.5 py-1 rounded bg-zinc-800 text-zinc-400 hover:text-zinc-200"
                title={`${r} 종료 발화`}
              >
                {r.split("_")[0]}
              </button>
            ))}
          </Group>

          <Group label="account">
            <button
              onClick={fireBalanceInvalidated}
              className="px-2 py-1 rounded bg-zinc-800 text-zinc-400 hover:text-zinc-200"
            >
              BALANCE_INVALIDATED
            </button>
          </Group>

          <p className="mt-3 text-[10px] text-zinc-600 leading-relaxed">
            실 STOMP는 자동으로 재연결됩니다. 발화 버튼은 mock 토스트/알림을 트리거합니다.
          </p>
        </div>
      )}
    </div>
  );
}

function Group({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="mb-3 last:mb-0">
      <div className="text-[10px] text-zinc-500 uppercase tracking-wider mb-1.5">
        {label}
      </div>
      <div className="flex flex-wrap gap-1">{children}</div>
    </div>
  );
}
