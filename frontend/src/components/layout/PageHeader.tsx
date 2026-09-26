import { useEffect, useState, type ReactNode } from "react";
import { formatRelative } from "../../lib/format";
import SessionStrip from "./SessionStrip";

/**
 * 화면 상단 공용 헤더 — 제목·장 상태 / 건수·갱신 상태.
 *
 * 왼쪽(제목+장 상태)과 오른쪽(건수+갱신 신호)을 **두 묶음으로 두고 접는다.**
 * 각 묶음 안에서 위아래가 짝지어 있어, 좁은 폭에서 오른쪽이 아래로 내려가도 어긋나지 않는다.
 * 격자로 열을 나누면 제목 열이 0까지 눌려 폰에서 제목이 글자 단위로 쪼개진다.
 *
 * 갱신 주기는 문구로 적지 않는다 — 점과 상대시각이 이미 말하고 있다.
 * 상대시각은 1초마다 다시 센다 — 폴링이 올 때만 다시 그리면 "방금"에 멈춰 있어 흐르는 게 안 보인다.
 */
export default function PageHeader({
  title,
  count,
  fetchedAt,
  refreshMs,
  loading,
  trailing,
}: {
  title: string;
  count?: number;
  /** 이 화면이 마지막으로 받아 온 시각(ms, TanStack `dataUpdatedAt`). 0이면 점만 보여준다.
   *  서버의 `queriedAt`은 쓰지 않는다 — 응답 시각일 뿐 시세를 받아 온 시각이 아니라, 휴장 중엔 캐시가 하루를 간다. */
  fetchedAt?: number;
  /** 자동 갱신 주기(ms). 주면 고리가 받은 순간부터 이 시간에 걸쳐 차오른다 — 다 차면 다음 갱신이다.
   *  없으면(지난 날짜처럼 다시 부르지 않는 화면) 고리를 흐린 채 채워 둔다. */
  refreshMs?: number;
  loading: boolean;
  /** 우측 상단에 건수와 나란히 놓을 컨트롤 (날짜 이동 등). */
  trailing?: ReactNode;
}) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!fetchedAt) return;
    setNow(Date.now()); // 새로 받자마자 "방금"부터 다시 센다
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [fetchedAt]);
  const ago = fetchedAt ? formatRelative(new Date(fetchedAt).toISOString(), new Date(now)) : undefined;

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
          <RefreshRing fetchedAt={fetchedAt ?? 0} refreshMs={refreshMs} loading={loading} />
          {ago}
        </span>
      </div>
    </div>
  );
}

/** 고리 둘레 — r=5. 채워지는 양을 dashoffset으로 줄여 간다. */
const RING = 2 * Math.PI * 5;

/**
 * 다음 갱신까지 남은 시간 — 받은 순간 비었다가 주기에 걸쳐 시계 방향으로 찬다.
 * 새로 받으면(`fetchedAt`이 바뀌면) key가 바뀌어 애니메이션이 처음부터 다시 돈다.
 */
function RefreshRing({ fetchedAt, refreshMs, loading }: { fetchedAt: number; refreshMs?: number; loading: boolean }) {
  return (
    <svg width="14" height="14" viewBox="0 0 14 14" className="-rotate-90" role="img" aria-label={loading ? "갱신 중" : "다음 갱신까지"}>
      <circle cx="7" cy="7" r="5" fill="none" strokeWidth="2" className="stroke-zinc-800" />
      {refreshMs && fetchedAt ? (
        <circle
          key={fetchedAt}
          cx="7"
          cy="7"
          r="5"
          fill="none"
          strokeWidth="2"
          strokeLinecap="round"
          className="refresh-ring stroke-accent"
          style={{ strokeDasharray: RING, animationDuration: `${refreshMs}ms`, ["--ring" as string]: RING }}
        />
      ) : (
        <circle cx="7" cy="7" r="5" fill="none" strokeWidth="2" className="stroke-zinc-600" />
      )}
    </svg>
  );
}
