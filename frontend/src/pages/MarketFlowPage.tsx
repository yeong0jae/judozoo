import { useRegimeDaily } from "../api/queries";
import RegimePanel from "../components/market/RegimePanel";
import RegimeDailyChart from "../components/market/RegimeDailyChart";
import { REGIME_SEGMENTS, computeRows } from "../lib/regimeSegments";

function fmtPct(v: number): string {
  return `${v >= 0 ? "+" : ""}${v.toFixed(2)}%`;
}

export default function MarketFlowPage() {
  const dailyQ = useRegimeDaily();
  const records = dailyQ.data ?? [];
  const chrono = [...records].reverse(); // 최신순 → 시간순(오래된 게 왼쪽)
  const rows = computeRows(chrono);

  // 두 줄이 같은 척도를 쓰도록 전체 도메인 산출
  const allVals = rows
    .flatMap((r) => r.segs)
    .filter((v): v is number => v !== null)
    .concat(0);
  let mn = Math.min(...allVals);
  let mx = Math.max(...allVals);
  const sp = mx - mn || 1;
  mn -= sp * 0.12;
  mx += sp * 0.12;
  const domain = { mn, mx };

  const row1 = rows.slice(0, 10);
  const row2 = rows.slice(10, 20);

  // 구간별 통계 (상승일수 / 평균)
  const stats = REGIME_SEGMENTS.map((seg, si) => {
    const vals = rows
      .map((r) => r.segs[si])
      .filter((v): v is number => v !== null);
    const up = vals.filter((v) => v > 0).length;
    const avg = vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : 0;
    return { seg, n: vals.length, up, avg };
  });

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-zinc-100">시장 흐름</h1>
      <RegimePanel />
      <div className="rounded-xl border border-zinc-800 bg-zinc-900/60 p-4">
        <h2 className="mb-1 text-sm font-semibold text-zinc-300">
          최근 20일 — 구간별 변동
        </h2>
        <p className="mb-3 text-xs leading-relaxed text-zinc-600">
          하루를 네 구간으로 나눠 <b className="text-zinc-400">직전 시점 대비</b> 변동을
          보여줍니다 (전일 20:00 → 08:15 → 11:00 → 14:00 → 20:00). 거래대금 상위
          30종목(ETF 제외) 등락률 기준. 막대에 마우스를 올리면 상세.
        </p>

        {rows.length === 0 ? (
          <div className="py-10 text-center text-sm text-zinc-600">데이터 누적 중…</div>
        ) : (
          <div className="space-y-1">
            <RegimeDailyChart rows={row1} domain={domain} columns={10} />
            {row2.length > 0 && (
              <RegimeDailyChart rows={row2} domain={domain} columns={10} />
            )}
          </div>
        )}

        <div className="mt-2 flex flex-wrap gap-4 text-xs text-zinc-500">
          {REGIME_SEGMENTS.map((s) => (
            <span key={s.key}>
              <span
                className="mr-1 inline-block h-2.5 w-2.5 rounded-sm align-middle"
                style={{ background: s.color }}
              />
              {s.key}
            </span>
          ))}
        </div>

        {rows.length > 0 && (
          <div className="mt-4 space-y-1.5 border-t border-zinc-800 pt-3 text-sm text-zinc-400">
            {stats.map(({ seg, n, up, avg }) => (
              <p key={seg.key}>
                <span className="mr-1" style={{ color: seg.color }}>
                  ●
                </span>
                {seg.prev} 대비 {seg.key}는{" "}
                {n === 0 ? (
                  <span className="text-zinc-600">데이터 없음</span>
                ) : (
                  <>
                    {n}일 중 <b className="text-zinc-200">{up}일</b> 상승 · 평균 등락률{" "}
                    <b className={avg >= 0 ? "text-emerald-400" : "text-rose-400"}>
                      {fmtPct(avg)}
                    </b>
                  </>
                )}
              </p>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
