import { motion } from "motion/react";
import { GradeAvatar, eok, pct, won } from "./parts";
import type { BetStock, Grade } from "./mock";
import { colorByPnL } from "../../lib/format";

export type StockRow = { stock: BetStock; grade: Grade; potMan: number; contrarian: boolean };

/** 순위 · 종목 · 몰린 판돈 · 가격 · 등락(또는 결과) — 오늘의 주도주 목록과 같은 열 */
const COLS = "grid-cols-[1.25rem_minmax(0,1fr)_6.5rem_5.5rem_4.5rem]";

const MEDAL = ["#f5c451", "#d4d8de", "#c98a5a"];
const MEDAL_INK = ["#1a1405", "#14161a", "#1f1006"];

/** 1,000만원 걸었다면 — 주식 수는 ⌊금액 ÷ 매수가⌋ */
const ifTenMillion = (s: BetStock) => Math.floor(10000000 / s.price) * s.price * ((s.result ?? 0) / 100);

/**
 * 종목 목록 — 오늘은 주도주 · 후보 구간으로 나눈다. 줄을 누르면 내 픽, 내 픽을 다시 누르면 해제.
 * 어제 판은 한 줄로 **오른 순**. 1~3위에 메달과 "1,000만원이면". 결과 발표 중 가려진 종목은 맨 아래에 두고,
 * 공개되면 제자리로 올라간다.
 */
export default function StockList({
  rows,
  selectedCode,
  mineCode,
  onPick,
  showResult,
  covered,
}: {
  rows: StockRow[];
  /** 지금 펼쳐 보는 줄 */
  selectedCode: string | null;
  /** 내가 종베한(오늘은 고른) 종목 */
  mineCode: string | null;
  onPick: (code: string) => void;
  /** 어제 판 — 가격 열은 매수가, 마지막 열은 다음 날 결과 */
  showResult: boolean;
  /** 결과 발표 중 — 아직 공개 안 된 종목은 결과 칸을 가린다 */
  covered?: Set<string>;
}) {
  const maxPot = Math.max(1, ...rows.map((r) => r.potMan));
  const isCovered = (r: StockRow) => !!covered?.has(r.stock.code);
  const groups: { label: string; hint?: string; rows: StockRow[] }[] = showResult
    ? [
        {
          label: "",
          rows: [...rows.filter((r) => !isCovered(r)).sort((a, b) => (b.stock.result ?? 0) - (a.stock.result ?? 0)), ...rows.filter(isCovered)],
        },
      ]
    : [
        { label: "주도주", rows: rows.filter((r) => r.stock.lead) },
        { label: "후보", hint: "거래대금 순", rows: rows.filter((r) => !r.stock.lead) },
      ];
  let rank = 0;

  return (
    <div aria-label="종목" className="flex min-w-0 flex-col">
      <div className={`grid ${COLS} gap-x-3 px-3.5 pb-1.5 text-xs text-zinc-500`}>
        <span />
        <span>종목</span>
        <span className="text-right">몰린 판돈</span>
        <span className="text-right">{showResult ? "매수가" : "현재가"}</span>
        <span className="text-right">{showResult ? "결과" : "등락률"}</span>
      </div>
      {groups.map((g) => (
        <div key={g.label}>
          {g.label && (
            <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 px-3.5 pb-1.5 pt-4">
              <span className="text-[15px] font-bold tracking-tight text-zinc-100">{g.label}</span>
              <span className="num text-xs text-zinc-400">{g.rows.length}</span>
              {g.hint && <span className="text-xs text-zinc-500">{g.hint}</span>}
            </div>
          )}
          {g.rows.map((r) => {
            rank += 1;
            const s = r.stock;
            const mine = s.code === mineCode;
            const selected = s.code === selectedCode;
            const v = showResult ? (s.result ?? 0) : s.chg;
            const medal = showResult && !isCovered(r) && rank <= 3 ? rank - 1 : -1;
            return (
              <motion.button
                layout="position"
                transition={{ duration: 0.35, ease: "easeOut" }}
                key={s.code}
                type="button"
                onClick={() => onPick(s.code)}
                aria-pressed={selected}
                className={`grid w-full ${COLS} items-center gap-x-3 rounded-xl px-3.5 py-2.5 text-left transition-colors ${selected ? "bg-selected" : "hover:bg-zinc-850"}`}
              >
                {medal >= 0 ? (
                  <span className="num inline-flex h-5 w-5 items-center justify-center justify-self-end rounded-full text-[11px] font-bold" style={{ background: MEDAL[medal], color: MEDAL_INK[medal] }}>
                    {rank}
                  </span>
                ) : (
                  <span className="num text-right text-xs text-zinc-500">{showResult && isCovered(r) ? "" : rank}</span>
                )}
                <span className="flex min-w-0 items-center gap-2.5">
                  <GradeAvatar grade={r.grade} />
                  <span className="flex min-w-0 flex-col gap-0.5">
                    <span className="flex min-w-0 items-center gap-1.5">
                      <span className="truncate text-sm text-zinc-100">{s.name}</span>
                      {mine && <span className="shrink-0 rounded-full bg-blue-50 px-2 py-px text-[11px] font-medium text-blue-700">{showResult ? "내 종베" : "내 픽"}</span>}
                    </span>
                    <span className="text-[11px] text-zinc-500">
                      {showResult ? (s.lead ? "주도주" : "후보") : <span className="num">{s.code}</span>} · <span className="num">{s.crowd}</span>명 고름
                      {r.contrarian && " · 거의 안 고름"}
                      {medal >= 0 && (
                        <span className="text-zinc-300">
                          {" "}
                          · 1,000만원이면 <span className="num">{ifTenMillion(s) > 0 ? "+" : ""}{won(ifTenMillion(s))}원</span>
                        </span>
                      )}
                    </span>
                  </span>
                </span>
                <span className="flex flex-col items-end gap-1.5">
                  <span className="num text-xs text-zinc-400">{eok(r.potMan)}</span>
                  <span className="relative h-0.5 w-full overflow-hidden rounded-full bg-zinc-850">
                    <span className="absolute inset-y-0 right-0 rounded-full bg-zinc-500" style={{ width: `${(r.potMan / maxPot) * 100}%` }} />
                  </span>
                </span>
                <span className="num text-right text-sm text-zinc-100">{won(s.price)}</span>
                {covered?.has(s.code) ? (
                  <span className="num text-right text-sm font-bold text-zinc-500">?</span>
                ) : (
                  <span className={`num text-right ${showResult ? "text-sm font-bold" : "text-xs"} ${colorByPnL(v)}`}>{pct(v)}</span>
                )}
              </motion.button>
            );
          })}
        </div>
      ))}
    </div>
  );
}
