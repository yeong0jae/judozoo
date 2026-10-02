import { useState, type ReactNode } from "react";
import { GradeAvatar, pct, useCountUp, won } from "./parts";
import { MY_YESTERDAY, NICKNAME_COOLDOWN_DAYS, NICKNAME_RULE, RANK_PROFIT, RANK_RATE, type BetStock, type Grade } from "./mock";

const MEDAL = ["#f5c451", "#d4d8de", "#c98a5a"];
const MEDAL_INK = ["#1a1405", "#14161a", "#1f1006"];
const PODIUM_HEIGHT = [96, 72, 56];

/** 페이지에서는 판 위 카드, 서랍 안에서는 한 단계 밝은 카드 */
const surfaceOf = (inDrawer: boolean) => (inDrawer ? "bg-zinc-850 px-5 py-[18px]" : "bg-zinc-900 px-6 py-[22px]");

/** 내 어제 종베 — 결과 발표 중에는 공개될 때까지 가려 두고, 공개되면 수익금이 차오른다 */
export function MyResultCard({ stock, grade, revealed = true, inDrawer = false }: { stock: BetStock; grade: Grade; revealed?: boolean; inDrawer?: boolean }) {
  const pnl = useCountUp(revealed ? MY_YESTERDAY.pnl : 0, 1400);
  const sell = Math.round(stock.price * (1 + (stock.result ?? 0) / 100) + 1e-6);
  const line = inDrawer ? "border-zinc-700" : "border-zinc-800";
  return (
    <section aria-label="내 어제 종베" className={`flex min-w-0 flex-col gap-[18px] rounded-[20px] ${surfaceOf(inDrawer)}`}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-[15px] font-bold">
          내 어제 종베 <span className="num font-normal text-zinc-500">9/30</span>
        </span>
        <span className="rounded-full bg-zinc-800 px-2.5 py-0.5 text-xs text-zinc-400">{revealed ? "정산 완료 · 10/1 09:05" : "공개 중…"}</span>
      </div>
      <div className="flex items-center gap-2.5">
        <GradeAvatar grade={grade} size={32} />
        <span className="flex min-w-0 flex-col gap-0.5">
          <span className="text-lg font-bold">{stock.name}</span>
          <span className="num text-xs text-zinc-400">
            {won(MY_YESTERDAY.amount)}만원 · {MY_YESTERDAY.shares}주
          </span>
        </span>
      </div>
      <div className="flex flex-col gap-1">
        <span className="num text-[40px] font-medium leading-tight tracking-tight text-red-400">{revealed ? `+${won(pnl)}원` : "? ? ?"}</span>
        <span className="num text-[15px] font-semibold text-red-400">{revealed ? pct(MY_YESTERDAY.rate) : " "}</span>
      </div>
      <div className={`grid grid-cols-3 border-t pt-3.5 ${line}`}>
        <span className="flex flex-col gap-1">
          <span className="text-xs text-zinc-400">매수가 · 9/30 20:00</span>
          <span className="num text-[15px] font-semibold">{won(stock.price)}</span>
        </span>
        <span className={`flex flex-col gap-1 border-l pl-3.5 ${line}`}>
          <span className="text-xs text-zinc-400">매도가 · 10/1 08:00</span>
          <span className="num text-[15px] font-semibold">{revealed ? won(sell) : "?"}</span>
        </span>
        <span className={`flex flex-col gap-1 border-l pl-3.5 ${line}`}>
          <span className="text-xs text-zinc-400">내 순위</span>
          <span className="num text-[15px] font-semibold">{revealed ? `${MY_YESTERDAY.profitRank}위 / ${MY_YESTERDAY.players}` : "?"}</span>
        </span>
      </div>
      {revealed && (
        <span className="text-[13px] text-zinc-400">
          상위 {Math.round((MY_YESTERDAY.profitRank / MY_YESTERDAY.players) * 100)}% · 바로 위 {MY_YESTERDAY.profitRank - 1}위와 {won(MY_YESTERDAY.gapToAbove)}원 차이였어요.
        </span>
      )}
    </section>
  );
}

/** 내 닉네임과 바꾸기 — 지금은 이 화면 안에서만 산다 */
export type Me = { nick: string; renamedAt: Date | null; rename: (nick: string) => void };

const TAKEN = new Set([...RANK_PROFIT, ...RANK_RATE].map((r) => r.nick));

/** 랭킹의 내 줄 — 연필을 누르면 그 자리에서 바꾼다. 바꾼 뒤 7일은 잠긴다 */
function NicknameEdit({ me }: { me: Me }) {
  const [draft, setDraft] = useState<string | null>(null);
  const nextAt = me.renamedAt ? new Date(me.renamedAt.getTime() + NICKNAME_COOLDOWN_DAYS * 86400000) : null;

  if (draft === null) {
    return (
      <span className="flex items-center gap-1.5 text-sm font-semibold">
        <span className="truncate">{me.nick}</span>
        <span className="text-[11px] font-medium text-blue-700">나</span>
        {nextAt ? (
          <span className="text-[11px] font-normal text-zinc-500">
            {nextAt.getMonth() + 1}/{nextAt.getDate()}부터 바꿀 수 있어요
          </span>
        ) : (
          <button type="button" onClick={() => setDraft(me.nick)} aria-label="닉네임 바꾸기" className="inline-flex h-5 w-5 items-center justify-center rounded text-zinc-500 hover:text-zinc-200">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4" />
            </svg>
          </button>
        )}
      </span>
    );
  }

  const v = draft.trim();
  const error = v === me.nick ? "" : !NICKNAME_RULE.test(v) ? "2~10자, 한글·영문·숫자만" : TAKEN.has(v) ? "이미 있는 닉네임이에요" : "";
  const canSave = v !== me.nick && !error;
  const save = () => {
    if (!canSave) return;
    me.rename(v);
    setDraft(null);
  };

  return (
    <span className="flex flex-col gap-1">
      <span className="flex items-center gap-1.5">
        <label htmlFor="cb-nick" className="sr-only">
          새 닉네임
        </label>
        <input
          id="cb-nick"
          autoFocus
          value={draft}
          maxLength={10}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") save();
            if (e.key === "Escape") setDraft(null);
          }}
          className="h-7 w-28 min-w-0 rounded-lg bg-zinc-950 px-2 text-sm text-zinc-100 outline-none ring-1 ring-zinc-700 focus:ring-zinc-400"
        />
        <button type="button" onClick={save} disabled={!canSave} className="h-7 rounded-lg bg-zinc-100 px-2.5 text-xs font-semibold text-zinc-950 disabled:opacity-40">
          저장
        </button>
        <button type="button" onClick={() => setDraft(null)} className="h-7 rounded-lg px-1.5 text-xs text-zinc-400 hover:text-zinc-200">
          취소
        </button>
      </span>
      <span className={`text-[11px] ${error ? "text-red-400" : "text-zinc-500"}`}>{error || `바꾸면 ${NICKNAME_COOLDOWN_DAYS}일 뒤에 다시 바꿀 수 있어요`}</span>
    </span>
  );
}

/** 어제의 판 랭킹 — 수익금 · 수익률 TOP 5와 내 순위 */
export function RankingCard({ myStock, me, inDrawer = false }: { myStock: string; me: Me; inDrawer?: boolean }) {
  const [byRate, setByRate] = useState(false);
  const ranks = byRate ? RANK_RATE : RANK_PROFIT;
  return (
    <section aria-label="어제의 판 랭킹" className={`flex min-w-0 flex-col gap-2.5 rounded-[20px] ${surfaceOf(inDrawer)}`}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-[15px] font-bold">
          어제의 판 랭킹 <span className="num font-normal text-zinc-500">{MY_YESTERDAY.players}명</span>
        </span>
        <div role="tablist" aria-label="랭킹 기준" className="flex rounded-xl bg-zinc-800 p-0.5 text-xs">
          {[
            ["수익금", false],
            ["수익률", true],
          ].map(([label, rate]) => (
            <button
              key={label as string}
              type="button"
              role="tab"
              aria-selected={byRate === rate}
              onClick={() => setByRate(rate as boolean)}
              className={`rounded-lg px-3 py-1.5 transition-colors ${byRate === rate ? "bg-elevated font-medium text-zinc-100" : "text-zinc-500 hover:text-zinc-300"}`}
            >
              {label as string}
            </button>
          ))}
        </div>
      </div>
      {/* 시상대 — 2 · 1 · 3 순서로 세워 1위가 가운데에서 가장 높다 */}
      <div className="mt-1 grid grid-cols-3 items-end gap-1.5">
        {[1, 0, 2].map((i) => {
          const r = ranks[i];
          return (
            <div key={r.nick + i} className="flex min-w-0 flex-col items-center gap-1.5 text-center">
              <span className="max-w-full truncate text-[13px] font-bold">{r.nick}</span>
              <span className="num text-[13px] font-bold text-red-400">{byRate ? r.rate : r.profit}</span>
              <span
                className="flex w-full flex-col items-center justify-center gap-0.5 rounded-b-[4px] rounded-t-xl"
                style={{ height: PODIUM_HEIGHT[i], background: MEDAL[i], color: MEDAL_INK[i] }}
              >
                <span className="num text-xl font-bold">{i + 1}</span>
                <span className="max-w-[90%] truncate text-[11px] opacity-80">{r.stock}</span>
              </span>
            </div>
          );
        })}
      </div>
      <ol className="m-0 flex list-none flex-col p-0">
        {ranks.slice(3).map((r, i) => (
          <li key={r.nick + i} className={`grid grid-cols-[1.5rem_minmax(0,1fr)_auto] items-center gap-x-3 border-b px-0.5 py-2.5 ${inDrawer ? "border-zinc-800" : "border-zinc-850"}`}>
            <span className="num text-[13px] font-bold text-zinc-500">{i + 4}</span>
            <span className="min-w-0 truncate text-sm">
              {r.nick} <span className="text-xs text-zinc-500">· {r.stock}</span>
            </span>
            <span className="num text-sm font-semibold text-red-400">{byRate ? r.rate : r.profit}</span>
          </li>
        ))}
      </ol>
      <div className={`grid grid-cols-[1.5rem_minmax(0,1fr)_auto] items-center gap-x-3 rounded-xl px-2 py-2.5 ${inDrawer ? "bg-zinc-800" : "bg-zinc-850"}`}>
        <span className="num text-center text-xs font-bold text-blue-700">{byRate ? MY_YESTERDAY.rateRank : MY_YESTERDAY.profitRank}</span>
        <span className="flex min-w-0 flex-col gap-px">
          <NicknameEdit me={me} />
          <span className="text-xs text-zinc-500">{myStock}</span>
        </span>
        <span className="flex flex-col items-end gap-px">
          <span className="num text-sm font-semibold text-red-400">{byRate ? pct(MY_YESTERDAY.rate) : "+41.9만"}</span>
          <span className="num text-[11px] text-zinc-500">{byRate ? "+41.9만" : pct(MY_YESTERDAY.rate)}</span>
        </span>
      </div>
    </section>
  );
}

/**
 * 어제 판 — 복기가 주인공. 내 결과 · 랭킹을 위에 두고, 그 아래 "아, 이거 종베할걸"에
 * 결과순 종목 목록(페이지가 `children`으로 넣는다)을 둔다.
 * 결과 발표 중에는 내 결과(`revealed`)를 공개될 때까지 가려 둔다.
 */
export default function YesterdayReview({
  stocks,
  me,
  revealed = true,
  children,
}: {
  stocks: { stock: BetStock; grade: Grade }[];
  me: Me;
  revealed?: boolean;
  children: ReactNode;
}) {
  const mine = stocks.find((s) => s.stock.code === MY_YESTERDAY.code)!;

  return (
    <div className="flex flex-col gap-6">
      <div className="grid grid-cols-1 items-stretch gap-4 xl:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
        <MyResultCard stock={mine.stock} grade={mine.grade} revealed={revealed} />
        <RankingCard myStock={mine.stock.name} me={me} />
      </div>

      <section aria-label="아, 이거 종베할걸" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-baseline justify-between gap-1.5">
          <h2 className="m-0 text-lg font-bold">아, 이거 종베할걸</h2>
          <span className="text-[13px] text-zinc-400">9/30 20:00 종가에 사서 10/1 아침에 팔았다면 · 오른 순</span>
        </div>

        {children}
      </section>
    </div>
  );
}
