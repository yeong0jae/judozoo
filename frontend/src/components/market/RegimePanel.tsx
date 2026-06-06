import { useEffect, useState } from "react";
import { useRegime } from "../../api/queries";
import { useStompSubscription } from "../../ws/useStompSubscription";
import type { RegimeSnapshot } from "../../types";

function fmtPct(v: number): string {
  return `${v >= 0 ? "+" : ""}${v.toFixed(2)}%`;
}

type Tone = "up" | "down" | "neutral";

// 관측(예측 아님): 08:15 대비 본장이 오르나 떨어지나로 현재 톤을 표시.
function gap2Tone(gap2: number | null): Tone {
  if (gap2 === null) return "neutral";
  if (gap2 > 0.3) return "up";
  if (gap2 < -0.3) return "down";
  return "neutral";
}

const TONE_TEXT: Record<Tone, string> = {
  up: "text-emerald-400",
  down: "text-rose-400",
  neutral: "text-zinc-400",
};

const TONE_LABEL: Record<Tone, string> = {
  up: "본장 상승 흐름",
  down: "본장 하락 흐름",
  neutral: "혼조 / 관망",
};

function Arrow({ value }: { value: number }) {
  const up = value >= 0;
  return (
    <span className={up ? "text-emerald-400" : "text-rose-400"}>
      {up ? "↗" : "↘"} {fmtPct(value)}
    </span>
  );
}

export default function RegimePanel() {
  const q = useRegime();
  const [snap, setSnap] = useState<RegimeSnapshot | null>(null);

  useEffect(() => {
    if (q.data !== undefined) setSnap(q.data ?? null);
  }, [q.data]);

  useStompSubscription<RegimeSnapshot>("/topic/regime", setSnap);

  if (!snap) {
    return (
      <div className="rounded-xl border border-zinc-800 bg-zinc-900/60 px-4 py-3 text-sm text-zinc-500">
        시장 레짐 — 집계 전 (본장 시간 08:05~15:30에 표시)
      </div>
    );
  }

  const tone = gap2Tone(snap.gap2);

  return (
    <div className="rounded-xl border border-zinc-800 bg-zinc-900/60 px-4 py-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-zinc-300">시장 레짐</h2>
        <span className={`text-sm font-semibold ${TONE_TEXT[tone]}`}>
          {TONE_LABEL[tone]}
        </span>
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <span className="text-zinc-500">전일종가</span>
        <Arrow value={snap.gap1} />
        <span className="text-zinc-300">
          아침 NXT{snap.gap1Locked ? "" : " (잠정)"}
        </span>
        {snap.gap2 !== null ? (
          <>
            <Arrow value={snap.gap2} />
            <span className="text-zinc-300">본장</span>
            {!snap.gap2Reliable && (
              <span className="text-xs text-amber-500">· 신뢰낮음</span>
            )}
          </>
        ) : (
          <span className="text-xs text-zinc-600">· 본장 전</span>
        )}
      </div>
    </div>
  );
}
