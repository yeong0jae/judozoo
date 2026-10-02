import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { GradeAvatar, md, pct, won } from "./parts";
import { MyResultCard, RankingCard, type Me } from "./YesterdayReview";
import type { ClosingBetRound } from "../../api/closingbet";
import { colorByPnL } from "../../lib/format";

const MEDAL = ["#f5c451", "#d4d8de", "#c98a5a"];
const MEDAL_INK = ["#1a1405", "#14161a", "#1f1006"];

/**
 * 베팅·대기 시간의 지난 판 — 한 줄로 결과를 보여 주고, 누르면 오른쪽 서랍에 복기를 펼친다.
 * 종목별 시장·수급 복기는 복기 시간(09:05~15:00) 화면에만 둔다.
 */
export default function LastRound({ round, me, member }: { round: ClosingBetRound; me: Me | null; member: boolean }) {
  const [open, setOpen] = useState(false);
  const mine = round.me;
  const myGrade = mine ? (round.stocks.find((s) => s.code === mine.stockCode)?.grade ?? null) : null;
  const best = round.stocks.filter((s) => s.rate !== null).sort((a, b) => (b.rate ?? 0) - (a.rate ?? 0));

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-expanded={open}
        className="flex w-full flex-wrap items-center gap-x-3.5 gap-y-1.5 rounded-[14px] bg-zinc-900 px-[18px] py-3 text-left transition-colors hover:bg-zinc-850"
      >
        <span className="text-xs text-zinc-400">
          지난 판 <span className="num">{md(round.day)}</span>
        </span>
        {mine && mine.pnl !== null && mine.rate !== null ? (
          <>
            <span className="flex items-center gap-2 text-sm">
              <GradeAvatar grade={myGrade} size={22} />
              {mine.stockName}
            </span>
            <span className={`num text-sm font-semibold ${mine.pnl >= 0 ? "text-red-400" : "text-blue-400"}`}>
              {mine.pnl >= 0 ? "+" : "−"}
              {won(Math.abs(mine.pnl))}원 <span className="font-medium">{pct(mine.rate)}</span>
            </span>
            {mine.profitRank && (
              <span className="num text-[13px] text-zinc-300">
                {mine.profitRank}위 / {round.players}
              </span>
            )}
          </>
        ) : (
          <span className="text-sm text-zinc-300">{member ? "이 판엔 안 걸었어요" : `${round.players}명이 걸었어요`}</span>
        )}
        {best[0] && (
          <span className="text-[13px] text-zinc-500">
            1위 종목 {best[0].name} {pct(best[0].rate ?? 0)}
          </span>
        )}
        <span className="ml-auto inline-flex items-center gap-1 text-[13px] font-semibold text-zinc-300">
          복기 보기
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="m9 6 6 6-6 6" />
          </svg>
        </span>
      </button>

      <AnimatePresence>
        {open && (
          <>
            <motion.div
              key="dim"
              aria-hidden
              onClick={() => setOpen(false)}
              className="fixed inset-0 z-40 bg-black/50"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
            />
            <motion.aside
              key="sheet"
              role="dialog"
              aria-modal="true"
              aria-label="지난 판 복기"
              className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[520px] flex-col gap-3.5 overflow-y-auto bg-zinc-900 p-5 shadow-[-12px_0_40px_rgba(0,0,0,0.4)]"
              initial={{ x: "100%" }}
              animate={{ x: 0 }}
              exit={{ x: "100%" }}
              transition={{ type: "tween", duration: 0.22, ease: "easeOut" }}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-lg font-bold">
                  지난 판 복기 <span className="num text-[13px] font-normal text-zinc-500">{md(round.day)}</span>
                </span>
                <button
                  type="button"
                  onClick={() => setOpen(false)}
                  aria-label="닫기"
                  className="inline-flex h-9 w-9 items-center justify-center rounded-[10px] bg-zinc-850 text-zinc-300 hover:text-zinc-100"
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden>
                    <path d="M6 6l12 12M18 6 6 18" />
                  </svg>
                </button>
              </div>

              <MyResultCard round={round} member={member} inDrawer />
              <RankingCard round={round} me={me} inDrawer />

              <section aria-label="아, 이거 종베할걸" className="flex flex-col gap-1.5 rounded-[18px] bg-zinc-850 px-5 py-[18px]">
                <span className="text-[15px] font-bold">아, 이거 종베할걸</span>
                {best.slice(0, 3).map((o, i) => (
                  <div key={o.code} className="grid grid-cols-[26px_minmax(0,1fr)_auto] items-center gap-x-3 py-2">
                    <span className="num inline-flex h-[26px] w-[26px] items-center justify-center rounded-full text-[13px] font-bold" style={{ background: MEDAL[i], color: MEDAL_INK[i] }}>
                      {i + 1}
                    </span>
                    <span className="flex min-w-0 flex-col gap-px">
                      <span className="text-sm font-semibold">{o.name}</span>
                      <span className="text-xs text-zinc-500">{o.crowd}명이 골랐어요</span>
                    </span>
                    <span className={`num text-[15px] font-semibold ${colorByPnL(o.rate ?? 0)}`}>{pct(o.rate ?? 0)}</span>
                  </div>
                ))}
              </section>
              <span className="text-xs leading-relaxed text-zinc-500">종목별 시장·수급 복기는 09:05~15:00 복기 시간에 펼쳐져요.</span>
            </motion.aside>
          </>
        )}
      </AnimatePresence>
    </>
  );
}
