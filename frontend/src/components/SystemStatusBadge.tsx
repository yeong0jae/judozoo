import { Link } from "react-router-dom";
import type { SystemStatus } from "../types";

interface Props {
  status: SystemStatus;
}

export default function SystemStatusBadge({ status }: Props) {
  return (
    <div className="flex items-center gap-2 text-xs">
      <Pill
        label={status.marketMode === "WS" ? "시세 정상" : "시세 폴링"}
        tone={status.marketMode === "WS" ? "ok" : "warn"}
        title={
          status.marketMode === "WS"
            ? "WebSocket 정상 수신 중"
            : "WebSocket 끊김 — REST 폴링 모드. 시그널 발동이 최대 1초 지연될 수 있음."
        }
      />
      <Pill
        label={status.tokenStatus === "OK" ? "토큰 OK" : "토큰 실패"}
        tone={status.tokenStatus === "OK" ? "ok" : "danger"}
        title={
          status.tokenStatus === "OK"
            ? "KIS 토큰 정상"
            : "KIS 토큰 갱신 실패 — 신규 명령 / 주문 차단"
        }
      />
      {status.isHoliday && <Pill label="휴장일" tone="danger" />}
      {status.unclosedCount > 0 && (
        <Link to="/report">
          <Pill
            label={`UNCLOSED ${status.unclosedCount}건`}
            tone="warn"
            title="시스템 다운/재시작 등으로 미처리된 명령 — 클릭 시 실적 화면"
          />
        </Link>
      )}
    </div>
  );
}

function Pill({
  label,
  tone,
  title,
}: {
  label: string;
  tone: "ok" | "warn" | "danger";
  title?: string;
}) {
  const cls =
    tone === "ok"
      ? "bg-emerald-900/40 text-emerald-300 border-emerald-800"
      : tone === "warn"
        ? "bg-amber-900/40 text-amber-300 border-amber-800"
        : "bg-rose-900/40 text-rose-300 border-rose-800";
  return (
    <span
      className={`px-2 py-1 rounded border ${cls} cursor-help`}
      title={title}
    >
      {label}
    </span>
  );
}
