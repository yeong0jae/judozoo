import type { ReactNode } from "react";
import SessionStrip from "./SessionStrip";

/**
 * 화면 상단 공용 헤더 — 제목·장 상태 / 건수·갱신 상태.
 *
 * 왼쪽(제목+장 상태)과 오른쪽(건수+갱신 신호)을 **두 묶음으로 두고 접는다.**
 * 각 묶음 안에서 위아래가 짝지어 있어, 좁은 폭에서 오른쪽이 아래로 내려가도 어긋나지 않는다.
 * 격자로 열을 나누면 제목 열이 0까지 눌려 폰에서 제목이 글자 단위로 쪼개진다.
 *
 * 갱신 주기는 문구로 적지 않는다 — 점과 상대시각이 이미 말하고 있다.
 */
export default function PageHeader({
  title,
  count,
  queriedAt,
  loading,
  trailing,
}: {
  title: string;
  count?: number;
  /** 마지막 조회 시각을 사람이 읽는 형태로 (예: "3초 전"). 없으면 점만 보여준다. */
  queriedAt?: string;
  loading: boolean;
  /** 우측 상단에 건수와 나란히 놓을 컨트롤 (날짜 이동 등). */
  trailing?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
      <div className="min-w-0">
        <h2 className="text-[20px] font-bold">{title}</h2>
        <SessionStrip />
      </div>

      <div className="flex flex-col items-end gap-1">
        <div className="flex items-center gap-3">
          {trailing}
          {typeof count === "number" && (
            <span className="flex items-baseline gap-1">
              <span className="text-lg font-bold num text-zinc-100">{count}</span>
              <span className="text-xs text-zinc-500">건</span>
            </span>
          )}
        </div>

        <span className="flex items-center gap-1.5 text-xs text-zinc-500 whitespace-nowrap">
          <span
            className={`inline-block w-1.5 h-1.5 rounded-full bg-accent ${
              loading ? "animate-ping" : "animate-pulse"
            }`}
            aria-label={loading ? "갱신 중" : "대기"}
          />
          {queriedAt}
        </span>
      </div>
    </div>
  );
}
