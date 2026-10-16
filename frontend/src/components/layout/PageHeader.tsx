import { useEffect, useState, type ReactNode } from "react";
import { formatRelative } from "../../lib/format";
import SessionStrip from "./SessionStrip";

export default function PageHeader({
  title,
  count,
  fetchedAt,
  refreshMs,
  closedLabel,
  loading,
  trailing,
  session = true,
}: {
  title: string;
  count?: number;
  fetchedAt?: number;
  refreshMs?: number;
  closedLabel?: string;
  loading: boolean;
  trailing?: ReactNode;
  session?: boolean;
}) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!fetchedAt) return;
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [fetchedAt]);
  const ago = fetchedAt ? formatRelative(new Date(fetchedAt).toISOString(), new Date(now)) : undefined;

  return (
    <div className="@container flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
      <div className="min-w-0">
        <h2 className="text-[20px] font-bold">{title}</h2>
        {session && <SessionStrip />}
      </div>

      <div className="flex flex-col items-end gap-1 @max-3xl:flex-row @max-3xl:flex-wrap @max-3xl:items-center @max-3xl:gap-3">
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
          {closedLabel ?? ago}
        </span>
      </div>
    </div>
  );
}

const RING = 2 * Math.PI * 5;

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
