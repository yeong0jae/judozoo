import type { ReactNode } from "react";
import SessionStrip from "./SessionStrip";

/**
 * 화면 상단 공용 헤더 — 제목·장 상태 / 건수·갱신 상태.
 *
 * 2행 그리드다. 좌측은 제목과 장 상태, 우측은 건수(1행)와 갱신 신호(2행).
 * baseline 정렬을 쓰면 좁은 폭에서 우측이 줄바꿈될 때 어긋나므로 격자로 고정한다.
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
    <div className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-4">
      <h2 className="text-xl font-bold">{title}</h2>

      <div className="flex items-center gap-3 justify-self-end">
        {trailing}
        {typeof count === "number" && (
          <span className="flex items-baseline gap-1">
            <span className="text-lg font-bold num text-zinc-100">{count}</span>
            <span className="text-xs text-zinc-500">건</span>
          </span>
        )}
      </div>

      <SessionStrip />

      <span className="flex items-center gap-1.5 justify-self-end text-xs text-zinc-500 whitespace-nowrap mt-1">
        <span
          className={`inline-block w-1.5 h-1.5 rounded-full bg-accent ${
            loading ? "animate-ping" : "animate-pulse"
          }`}
          aria-label={loading ? "갱신 중" : "대기"}
        />
        {queriedAt}
      </span>
    </div>
  );
}
