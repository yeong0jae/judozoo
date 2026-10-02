import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { DivergingBar, GradeAvatar, won } from "./parts";
import { GRADE_TEXT, type Check, type Grade } from "./model";

/**
 * 종베 체크를 한 장씩 — 조건 다섯 장 + 마지막 등급 요약 한 장.
 * 카드를 누르거나 ›를 누르면 다음 장이 옆에서 들어온다. 종목이 바뀌면 첫 장부터.
 */
export default function CheckPager({ code, name, grade, checks }: { code: string; name: string; grade: Grade; checks: Check[] }) {
  const pages = checks.length + 1;
  const [page, setPage] = useState(0);
  const [dir, setDir] = useState(1);
  useEffect(() => {
    setPage(0);
    setDir(1);
  }, [code]);

  const go = (to: number, d: number) => {
    setDir(d);
    setPage((to + pages) % pages);
  };
  const passed = checks.filter((c) => c.ok).length;
  const isSummary = page === pages - 1;
  const it = checks[page];

  return (
    <section aria-label="종베 체크" className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-2 px-1">
        <span className="flex min-w-0 items-center gap-2">
          <GradeAvatar grade={grade} size={26} />
          <span className="truncate text-[15px] font-bold">{name}</span>
          <span className="shrink-0 text-xs text-zinc-500">종베 체크</span>
        </span>
        <span className="num shrink-0 text-xs text-zinc-400">{isSummary ? "요약" : `${page + 1} / ${pages - 1}`}</span>
      </div>

      <div className="relative overflow-hidden rounded-2xl">
        <AnimatePresence mode="popLayout" initial={false} custom={dir}>
          <motion.button
            key={`${code}-${page}`}
            type="button"
            onClick={() => go(page + 1, 1)}
            aria-label="다음 조건"
            custom={dir}
            initial={{ x: dir * 36, opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            exit={{ x: -dir * 36, opacity: 0 }}
            transition={{ duration: 0.28, ease: [0.2, 0.8, 0.3, 1] }}
            className="flex min-h-[320px] w-full flex-col gap-4 rounded-2xl bg-zinc-900 p-6 text-left"
          >
            {!isSummary && it ? (
              <div className="flex flex-col gap-4">
                <span className={`self-start text-[13px] font-bold ${it.ok ? "text-[#4ade80]" : "text-red-400"}`}>
                  {it.ok ? "✓ 충족" : "✕ 미충족"} · {it.title}
                </span>
                <span className="text-[22px] font-bold leading-snug tracking-tight">{it.question}</span>
                <span className={`num text-[40px] font-medium leading-none tracking-tight ${it.ok ? "text-zinc-100" : "text-red-400"}`}>{it.value}</span>
                <span className="text-sm leading-relaxed text-zinc-300">{it.sentence}</span>
                {it.range && (
                  <div className="flex flex-col gap-1.5">
                    <span className="relative block h-2 rounded-full bg-zinc-800">
                      <span className="absolute inset-y-0 right-0 rounded-r-full bg-[#4ade80]/30" style={{ left: `${Math.max(0, Math.min(1, it.range.zoneStart)) * 100}%` }} />
                      <span
                        className="absolute -top-1 h-4 w-1 rounded-sm bg-zinc-100 ring-2 ring-zinc-900"
                        style={{ left: `calc(${Math.max(0, Math.min(1, it.range.pos)) * 100}% - 2px)` }}
                      />
                    </span>
                    <span className="num flex justify-between text-[11px] text-zinc-500">
                      <span>저가 {won(it.range.low)}</span>
                      <span>고가 {won(it.range.high)}</span>
                    </span>
                  </div>
                )}
                {it.flows && (
                  <div className="flex flex-col gap-2">
                    {it.flows.map((f) => (
                      <div key={f.label} className="grid grid-cols-[2.4rem_minmax(0,1fr)_5rem] items-center gap-2.5">
                        <span className="text-xs text-zinc-400">{f.label}</span>
                        <DivergingBar value={f.value} max={f.max} />
                        <span className={`num text-right text-[13px] font-semibold ${f.value > 0 ? "text-red-400" : "text-blue-400"}`}>
                          {f.value > 0 ? "+" : ""}
                          {won(f.value)}억
                        </span>
                      </div>
                    ))}
                  </div>
                )}
                <span className="text-xs leading-relaxed text-zinc-500">{it.why}</span>
              </div>
            ) : (
              <div className="flex flex-1 flex-col items-center justify-center gap-3.5 text-center">
                <span className="text-[13px] text-zinc-400">다섯 가지 중</span>
                <span className="num text-[40px] font-bold leading-none tracking-tight">
                  {passed}
                  <span className="text-[22px] text-zinc-400"> / {pages - 1}</span>
                </span>
                <GradeAvatar grade={grade} size={64} />
                <span className="text-[15px] font-bold">{GRADE_TEXT[grade]}</span>
                <span className="flex flex-wrap justify-center gap-1.5">
                  {checks.map((c) => (
                    <span key={c.key} className={`rounded-full bg-zinc-850 px-2.5 py-0.5 text-xs ${c.ok ? "text-zinc-300" : "text-red-400"}`}>
                      {c.ok ? "✓" : "✕"} {c.title}
                    </span>
                  ))}
                </span>
              </div>
            )}
            <span className="mt-auto self-end text-xs text-zinc-500">
              {isSummary ? "눌러서 처음부터 ›" : page === pages - 2 ? "눌러서 결과 보기 ›" : "눌러서 다음 ›"}
            </span>
          </motion.button>
        </AnimatePresence>
      </div>

      <div className="flex items-center justify-between gap-2 px-1">
        <button type="button" onClick={() => go(page - 1, -1)} aria-label="이전 조건" className="inline-flex h-9 w-9 items-center justify-center rounded-[10px] bg-zinc-900 text-zinc-300 hover:bg-zinc-850">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M15 6l-6 6 6 6" />
          </svg>
        </button>
        <span className="flex gap-1.5">
          {Array.from({ length: pages }).map((_, i) => {
            const on = i === page;
            const c = checks[i];
            return (
              <button
                key={i}
                type="button"
                onClick={() => go(i, i < page ? -1 : 1)}
                aria-label={c ? `${i + 1}번째 조건 ${c.title}` : "요약"}
                className="h-[7px] rounded-full transition-[width] duration-200"
                style={{ width: on ? 18 : 7, background: on ? "#f1f3f5" : c ? (c.ok ? "rgba(74,222,128,0.6)" : "rgba(248,113,113,0.6)") : "#4e5968" }}
              />
            );
          })}
        </span>
        <button type="button" onClick={() => go(page + 1, 1)} aria-label="다음 조건" className="inline-flex h-9 w-9 items-center justify-center rounded-[10px] bg-zinc-900 text-zinc-300 hover:bg-zinc-850">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M9 6l6 6-6 6" />
          </svg>
        </button>
      </div>
    </section>
  );
}
