import { useEffect, useRef, useState } from "react";
import { GRADE_COLOR, type Grade } from "./mock";

export const won = (n: number) => Math.round(n).toLocaleString("ko-KR");
export const signed = (n: number) => `${n > 0 ? "+" : n < 0 ? "−" : ""}${won(Math.abs(n))}`;
export const pct = (n: number) => `${n > 0 ? "+" : ""}${n.toFixed(2)}%`;

/** 만원 → "4억 8,500만" */
export function eok(man: number): string {
  const e = Math.floor(Math.abs(man) / 10000);
  const r = Math.round(Math.abs(man) % 10000);
  const sign = man < 0 ? "−" : "";
  return sign + (e ? `${e}억` : "") + (r ? `${e ? " " : ""}${won(r)}만` : e ? "" : "0");
}

/** 등급 — 앱의 종목 아바타처럼 회색 면에 글자만 금·은·동·철 */
export function GradeAvatar({ grade, size = 24 }: { grade: Grade; size?: number }) {
  return (
    <span
      title="종베 등급"
      className="num inline-flex shrink-0 items-center justify-center bg-[#2b2d33] font-bold ring-1 ring-inset ring-white/10"
      style={{ width: size, height: size, borderRadius: Math.round(size / 3.2), fontSize: Math.round(size * 0.5), color: GRADE_COLOR[grade] }}
    >
      {grade}
    </span>
  );
}

/** 0을 가운데 둔 양방향 막대 — 순매수 빨강(오른쪽), 순매도 파랑(왼쪽) */
export function DivergingBar({ value, max, height = 8 }: { value: number; max: number; height?: number }) {
  const w = max ? (Math.abs(value) / max) * 50 : 0;
  return (
    <span className="relative block rounded-full bg-zinc-800" style={{ height }}>
      <span className="absolute -inset-y-0.5 left-1/2 w-px bg-zinc-600" />
      <span
        className={`absolute inset-y-0 rounded-full ${value >= 0 ? "left-1/2 bg-red-400" : "right-1/2 bg-blue-400"}`}
        style={{ width: `${w}%` }}
      />
    </span>
  );
}

/** 목표값이 바뀌면 0.7초에 걸쳐 숫자가 차오른다(처음엔 빠르게, 끝에서 천천히) */
export function useCountUp(target: number, duration = 700): number {
  const [shown, setShown] = useState(target);
  const from = useRef(target);
  const shownRef = useRef(target);
  shownRef.current = shown;
  useEffect(() => {
    from.current = shownRef.current;
    const start = performance.now();
    let raf = 0;
    const step = (t: number) => {
      const p = Math.min(1, (t - start) / duration);
      const e = 1 - Math.pow(1 - p, 3);
      setShown(from.current + (target - from.current) * e);
      if (p < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [target, duration]);
  return shown;
}
