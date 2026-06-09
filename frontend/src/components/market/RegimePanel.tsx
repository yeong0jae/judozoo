import { useEffect, useState } from "react";
import { useRegime, useRegimeDaily } from "../../api/queries";
import { useStompSubscription } from "../../ws/useStompSubscription";
import { REGIME_SEGMENTS, rel } from "../../lib/regimeSegments";
import type { RegimeSnapshot, RegimeDaily } from "../../types";

function fmtPct(v: number): string {
  return `${v >= 0 ? "+" : ""}${v.toFixed(2)}%`;
}

const OPEN_MIN = 9 * 60; // 정규장 개장 09:00 (KST) — 이 전엔 본장 구간 미표시
const CLOSE_MIN = 20 * 60; // NXT 애프터마켓 마감 20:00 (KST)

function kstNowMinutes(): number {
  const t = new Date().toLocaleTimeString("en-GB", {
    timeZone: "Asia/Seoul",
    hour12: false,
  });
  const [h, m] = t.split(":").map(Number);
  return h * 60 + m;
}

function kstToday(): string {
  return new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" });
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

type SegEntry = { value: number; live: boolean } | null;

/**
 * 4구간(오전 NXT / 오전 정규장 / 오후 정규장 / 오후 NXT)의 현재 표시값.
 * 확정된 구간은 체크포인트(today), 진행 중 구간은 라이브 gap2로 부분값을 낸다.
 * [liveGap2]는 09:00 게이트가 적용된 현재 본장 갭(그 전엔 null).
 */
function computeSegments(
  snap: RegimeSnapshot,
  today: RegimeDaily | null,
  liveGap2: number | null,
): SegEntry[] {
  // 오전 정규장(08:15→11:00): 11:00 지나면 확정, 진행 중이면 현재 gap2.
  const morning: SegEntry =
    today?.gap2At1100 != null
      ? { value: today.gap2At1100, live: false }
      : liveGap2 != null
        ? { value: liveGap2, live: true }
        : null;

  // 오후 정규장(11:00→14:00): 11:00 확정값 기준 14:00까지의 변동.
  const afternoon: SegEntry =
    today?.gap2At1100 == null
      ? null
      : today.gap2At1400 != null
        ? { value: rel(today.gap2At1100, today.gap2At1400), live: false }
        : liveGap2 != null
          ? { value: rel(today.gap2At1100, liveGap2), live: true }
          : null;

  // 오후 NXT(14:00→20:00): 14:00 확정값 기준 20:00까지의 변동.
  const afterMarket: SegEntry =
    today?.gap2At1400 == null
      ? null
      : today.gap2At2000 != null
        ? { value: rel(today.gap2At1400, today.gap2At2000), live: false }
        : liveGap2 != null
          ? { value: rel(today.gap2At1400, liveGap2), live: true }
          : null;

  return [
    { value: snap.gap1, live: !snap.gap1Locked }, // 오전 NXT
    morning,
    afternoon,
    afterMarket,
  ];
}

function SegRow({
  seg,
  entry,
}: {
  seg: (typeof REGIME_SEGMENTS)[number];
  entry: SegEntry;
}) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-zinc-400">
        <span className="mr-1.5" style={{ color: seg.color }}>
          ●
        </span>
        {seg.key}{" "}
        <span className="text-xs text-zinc-600">
          ({seg.from}→{seg.to})
        </span>
      </span>
      {entry === null ? (
        <span className="text-zinc-600">—</span>
      ) : (
        <span
          className={`font-semibold tabular-nums ${
            entry.value >= 0 ? "text-emerald-400" : "text-rose-400"
          }`}
        >
          {entry.value >= 0 ? "↗" : "↘"} {fmtPct(entry.value)}
          {entry.live && (
            <span className="ml-1 text-[10px] font-normal text-zinc-500">진행중</span>
          )}
        </span>
      )}
    </div>
  );
}

export default function RegimePanel() {
  const q = useRegime();
  const dailyQ = useRegimeDaily();
  const [snap, setSnap] = useState<RegimeSnapshot | null>(null);

  useEffect(() => {
    if (q.data !== undefined) setSnap(q.data ?? null);
  }, [q.data]);

  useStompSubscription<RegimeSnapshot>("/topic/regime", setSnap);

  // 20:00 이후 '마감' 전환 — 데이터 갱신이 멈춰도 시간으로 바뀌도록 주기적 재평가
  const [, setTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setTick((t) => t + 1), 30_000);
    return () => clearInterval(id);
  }, []);

  if (!snap) {
    return (
      <div className="rounded-xl border border-zinc-800 bg-zinc-900/60 px-4 py-3 text-sm text-zinc-500">
        시장 흐름 — 집계 전 (08:05 이후 표시)
      </div>
    );
  }

  const nowMin = kstNowMinutes();
  const closed = nowMin >= CLOSE_MIN;
  // 본장(08:15 대비) 갭은 09:00 정규장 개장부터 표시 — 그 전 NXT 프리마켓 구간은 숨김.
  const liveGap2 = nowMin < OPEN_MIN ? null : snap.gap2;
  const tone = gap2Tone(liveGap2);

  const today = (dailyQ.data ?? []).find((r) => r.date === kstToday()) ?? null;
  const segments = computeSegments(snap, today, liveGap2);

  return (
    <div className="rounded-xl border border-zinc-800 bg-zinc-900/60 px-4 py-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-zinc-300">시장 흐름</h2>
        {closed ? (
          <span className="text-sm text-zinc-500">마감</span>
        ) : liveGap2 === null ? (
          <span className="text-sm text-zinc-600">본장 전</span>
        ) : (
          <span className={`text-sm font-semibold ${TONE_TEXT[tone]}`}>
            {TONE_LABEL[tone]}
          </span>
        )}
      </div>

      <div className="mt-3 space-y-1.5 text-sm">
        {REGIME_SEGMENTS.map((seg, i) => (
          <SegRow key={seg.key} seg={seg} entry={segments[i]} />
        ))}
      </div>

      <p className="mt-2 text-[11px] text-zinc-600">
        거래대금 상위 30종목(ETF 제외) 등락률 · 직전 시점 대비
      </p>
    </div>
  );
}
