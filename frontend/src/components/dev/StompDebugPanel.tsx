// Dev-only: STOMP / 인지 채널 시뮬레이터.
// Phase 5-B-2 wire 후에도 mock 발화 시나리오 재현용으로 유지 (실 STOMP 연결과 분리).

import { useState } from "react";
import type { CloseReason } from "../../types";
import { useStompState, type ConnectionState } from "../../ws/stompMock";
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
  const stomp = useStompState();
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

  return (
    <div className="fixed bottom-6 left-6 z-40">
      {!open ? (
        <button
          onClick={() => setOpen(true)}
          className="text-xs bg-zinc-900 border border-zinc-700 text-zinc-400 hover:text-zinc-100 px-3 py-1.5 rounded shadow"
          title="개발 모드 STOMP 시뮬레이터"
        >
          🛠 dev
        </button>
      ) : (
        <div className="bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl p-3 w-72 text-xs">
          <div className="flex items-center justify-between mb-3">
            <span className="font-semibold text-zinc-300">
              🛠 STOMP 시뮬레이터
            </span>
            <button
              onClick={() => setOpen(false)}
              className="text-zinc-500 hover:text-zinc-200"
            >
              ×
            </button>
          </div>

          <Group label="STOMP 연결">
            {(["connected", "reconnecting", "disconnected"] as ConnectionState[]).map(
              (s) => (
                <button
                  key={s}
                  onClick={() => stomp.setState(s)}
                  className={`px-2 py-1 rounded ${
                    stomp.state === s
                      ? "bg-zinc-700 text-zinc-100"
                      : "bg-zinc-800 text-zinc-400 hover:text-zinc-200"
                  }`}
                >
                  {s}
                </button>
              ),
            )}
          </Group>

          <Group label="lifecycle CLOSED">
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
            Phase 5-B-2에서 실 STOMP 연결로 교체. 본 패널은 mock 시나리오 재현용으로 유지.
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
