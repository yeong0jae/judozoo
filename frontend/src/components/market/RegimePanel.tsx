import { useEffect, useState } from "react";
import { useRegime } from "../../api/queries";
import { useStompSubscription } from "../../ws/useStompSubscription";
import type { RegimeSnapshot } from "../../types";

function fmtPct(v: number): string {
  return `${v >= 0 ? "+" : ""}${v.toFixed(2)}%`;
}

const CLOSE_MIN = 15 * 60 + 30; // 본장 마감 15:30 (KST)

function kstNowMinutes(): number {
  const t = new Date().toLocaleTimeString("en-GB", {
    timeZone: "Asia/Seoul",
    hour12: false,
  });
  const [h, m] = t.split(":").map(Number);
  return h * 60 + m;
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
  neutral: "보합",
};

function GapRow({ label, value }: { label: string; value: number }) {
  const up = value >= 0;
  return (
    <div className="flex items-center justify-between">
      <span className="text-zinc-400">{label}</span>
      <span
        className={`font-semibold tabular-nums ${
          up ? "text-emerald-400" : "text-rose-400"
        }`}
      >
        {up ? "↗" : "↘"} {fmtPct(value)}
      </span>
    </div>
  );
}

export default function RegimePanel() {
  const q = useRegime();
  const [snap, setSnap] = useState<RegimeSnapshot | null>(null);

  useEffect(() => {
    if (q.data !== undefined) setSnap(q.data ?? null);
  }, [q.data]);

  useStompSubscription<RegimeSnapshot>("/topic/regime", setSnap);

  // 15:30 이후 '마감' 전환 — 데이터 갱신이 멈춰도 시간으로 바뀌도록 주기적 재평가
  const [, setTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setTick((t) => t + 1), 30_000);
    return () => clearInterval(id);
  }, []);

  if (!snap) {
    return (
      <div className="rounded-xl border border-zinc-800 bg-zinc-900/60 px-4 py-3 text-sm text-zinc-500">
        시장 흐름 — 집계 전 (본장 시간 08:05~15:30에 표시)
      </div>
    );
  }

  const closed = kstNowMinutes() >= CLOSE_MIN;
  const tone = gap2Tone(snap.gap2);

  return (
    <div className="rounded-xl border border-zinc-800 bg-zinc-900/60 px-4 py-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-zinc-300">시장 흐름</h2>
        {closed ? (
          <span className="text-sm text-zinc-500">마감</span>
        ) : snap.gap2 === null ? (
          <span className="text-sm text-zinc-600">본장 전</span>
        ) : (
          <span className={`text-sm font-semibold ${TONE_TEXT[tone]}`}>
            {TONE_LABEL[tone]}
          </span>
        )}
      </div>

      <div className="mt-3 space-y-1.5 text-sm">
        <GapRow
          label={`전일 종가 대비 아침 NXT${snap.gap1Locked ? " (08:15)" : " (잠정)"}`}
          value={snap.gap1}
        />
        {snap.gap2 !== null ? (
          <GapRow
            label={closed ? "아침 NXT 대비 종가" : "아침 NXT 대비 본장 (현재)"}
            value={snap.gap2}
          />
        ) : (
          <div className="flex items-center justify-between text-zinc-600">
            <span>아침 NXT 대비 본장 (현재)</span>
            <span className="text-xs">본장 시작 전</span>
          </div>
        )}
      </div>
    </div>
  );
}
