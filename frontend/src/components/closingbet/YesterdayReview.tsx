import { useState, type ReactNode } from "react";
import { GradeAvatar, md, pct, signedMan, useCountUp, won } from "./parts";
import { NICKNAME_COOLDOWN_DAYS, NICKNAME_RULE } from "./model";
import type { ClosingBetMyResult, ClosingBetRankRow, ClosingBetRound } from "../../api/closingbet";
import { goLogin } from "../../api/auth";
import { ApiError } from "../../api/client";

const MEDAL = ["#f5c451", "#d4d8de", "#c98a5a"];
const MEDAL_INK = ["#1a1405", "#14161a", "#1f1006"];
const PODIUM_HEIGHT = [96, 72, 56];

/** 페이지에서는 판 위 카드, 서랍 안에서는 한 단계 밝은 카드 */
const surfaceOf = (inDrawer: boolean) => (inDrawer ? "bg-zinc-850 px-5 py-[18px]" : "bg-zinc-900 px-6 py-[22px]");

/** 내 닉네임과 바꾸기. 닉네임은 첫 베팅 때 생긴다 */
export type Me = { nick: string | null; nextRenameAt: Date | null; rename: (nick: string) => Promise<unknown> };

/** 내 지난 판 — 결과 발표 중에는 내 종목이 공개될 때까지 가려 두고, 공개되면 수익금이 차오른다 */
export function MyResultCard({ round, member, inDrawer = false }: { round: ClosingBetRound; member: boolean; inDrawer?: boolean }) {
  const result = round.me;
  const revealed = result?.pnl !== null && result?.pnl !== undefined;
  const pnl = useCountUp(revealed ? (result?.pnl ?? 0) : 0, 1400);
  const surface = `flex min-w-0 flex-col gap-[18px] rounded-[20px] ${surfaceOf(inDrawer)}`;
  const head = (
    <span className="text-[15px] font-bold">
      내 종베 <span className="num font-normal text-zinc-500">{md(round.day)}</span>
    </span>
  );

  if (!member || !result) {
    return (
      <section aria-label="내 지난 종베" className={surface}>
        {head}
        <div className="flex flex-1 flex-col items-start justify-center gap-3 py-4">
          <span className="text-[15px] text-zinc-300">{member ? "이 판에는 걸지 않았어요." : "로그인하면 내 결과와 순위를 볼 수 있어요."}</span>
          {!member && (
            <button type="button" onClick={goLogin} className="h-9 rounded-xl bg-zinc-100 px-4 text-[13px] font-semibold text-zinc-950 hover:bg-white">
              로그인
            </button>
          )}
        </div>
      </section>
    );
  }

  const grade = round.stocks.find((s) => s.code === result.stockCode)?.grade ?? null;
  const tone = (result.pnl ?? 0) >= 0 ? "text-red-400" : "text-blue-400";
  const line = inDrawer ? "border-zinc-700" : "border-zinc-800";
  const top = result.profitRank ? Math.max(1, Math.round((result.profitRank / Math.max(1, round.players)) * 100)) : null;
  return (
    <section aria-label="내 지난 종베" className={surface}>
      <div className="flex items-center justify-between gap-2">
        {head}
        <span className="rounded-full bg-zinc-800 px-2.5 py-0.5 text-xs text-zinc-400">{revealed ? (round.settled ? "정산 완료" : "매도 완료") : "공개 대기"}</span>
      </div>
      <div className="flex items-center gap-2.5">
        <GradeAvatar grade={grade} size={32} />
        <span className="flex min-w-0 flex-col gap-0.5">
          <span className="text-lg font-bold">{result.stockName}</span>
          <span className="num text-xs text-zinc-400">
            {won(result.amountMan)}만원{result.shares ? ` · ${won(result.shares)}주` : ""}
          </span>
        </span>
      </div>
      <div className="flex flex-col gap-1">
        <span className={`num text-[40px] font-medium leading-tight tracking-tight ${revealed ? tone : "text-zinc-500"}`}>
          {revealed ? `${pnl >= 0 ? "+" : "−"}${won(Math.abs(pnl))}원` : "? ? ?"}
        </span>
        <span className={`num text-[15px] font-semibold ${tone}`}>{revealed && result.rate !== null ? pct(result.rate) : " "}</span>
      </div>
      <div className={`grid grid-cols-3 border-t pt-3.5 ${line}`}>
        <span className="flex flex-col gap-1">
          <span className="text-xs text-zinc-400">매수가 · {md(round.day)} 20:00</span>
          <span className="num text-[15px] font-semibold">{result.buyPrice ? won(result.buyPrice) : "—"}</span>
        </span>
        <span className={`flex flex-col gap-1 border-l pl-3.5 ${line}`}>
          <span className="text-xs text-zinc-400">매도가 · 아침 5분</span>
          <span className="num text-[15px] font-semibold">{result.sellPrice !== null ? won(result.sellPrice) : "?"}</span>
        </span>
        <span className={`flex flex-col gap-1 border-l pl-3.5 ${line}`}>
          <span className="text-xs text-zinc-400">내 순위</span>
          <span className="num text-[15px] font-semibold">{result.profitRank ? `${result.profitRank}위 / ${round.players}` : "?"}</span>
        </span>
      </div>
      {top !== null && <span className="text-[13px] text-zinc-400">상위 {top}%</span>}
    </section>
  );
}

/** 랭킹의 내 줄 — 연필을 누르면 그 자리에서 바꾼다. 바꾼 뒤 7일은 잠긴다 */
function NicknameEdit({ me }: { me: Me }) {
  const [draft, setDraft] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const locked = me.nextRenameAt !== null && me.nextRenameAt.getTime() > Date.now();

  if (me.nick === null) {
    return <span className="text-sm text-zinc-400">첫 베팅 때 닉네임이 생겨요</span>;
  }

  if (draft === null) {
    return (
      <span className="flex items-center gap-1.5 text-sm font-semibold">
        <span className="truncate">{me.nick}</span>
        <span className="text-[11px] font-medium text-blue-700">나</span>
        {locked ? (
          <span className="text-[11px] font-normal text-zinc-500">
            {me.nextRenameAt!.getMonth() + 1}/{me.nextRenameAt!.getDate()}부터 바꿀 수 있어요
          </span>
        ) : (
          <button
            type="button"
            onClick={() => {
              setDraft(me.nick);
              setError("");
            }}
            aria-label="닉네임 바꾸기"
            className="inline-flex h-5 w-5 items-center justify-center rounded text-zinc-500 hover:text-zinc-200"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4" />
            </svg>
          </button>
        )}
      </span>
    );
  }

  const v = draft.trim();
  const hint = v === me.nick ? "" : !NICKNAME_RULE.test(v) ? "2~10자, 한글·영문·숫자만" : "";
  const canSave = v !== me.nick && !hint && !busy;
  const save = async () => {
    if (!canSave) return;
    setBusy(true);
    try {
      await me.rename(v);
      setDraft(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "바꾸지 못했어요. 잠시 뒤 다시 해 주세요.");
    } finally {
      setBusy(false);
    }
  };
  const shown = error || hint;

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
          onChange={(e) => {
            setDraft(e.target.value);
            setError("");
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter") void save();
            if (e.key === "Escape") setDraft(null);
          }}
          className="h-7 w-28 min-w-0 rounded-lg bg-zinc-950 px-2 text-sm text-zinc-100 outline-none ring-1 ring-zinc-700 focus:ring-zinc-400"
        />
        <button type="button" onClick={() => void save()} disabled={!canSave} className="h-7 rounded-lg bg-zinc-100 px-2.5 text-xs font-semibold text-zinc-950 disabled:opacity-40">
          저장
        </button>
        <button type="button" onClick={() => setDraft(null)} className="h-7 rounded-lg px-1.5 text-xs text-zinc-400 hover:text-zinc-200">
          취소
        </button>
      </span>
      <span className={`text-[11px] ${shown ? "text-red-400" : "text-zinc-500"}`}>{shown || `바꾸면 ${NICKNAME_COOLDOWN_DAYS}일 뒤에 다시 바꿀 수 있어요`}</span>
    </span>
  );
}

/** 판 랭킹 — 수익금 · 수익률. 1~3위는 시상대, 그 아래는 한 줄씩, 맨 아래 내 줄 */
export function RankingCard({ round, me, inDrawer = false }: { round: ClosingBetRound; me: Me | null; inDrawer?: boolean }) {
  const [byRate, setByRate] = useState(false);
  const ranks: ClosingBetRankRow[] = byRate ? round.byRate : round.byProfit;
  const value = (r: { pnl: number; rate: number }) => (byRate ? pct(r.rate) : signedMan(r.pnl));
  const mine: ClosingBetMyResult | null = round.me;
  const myRank = byRate ? mine?.rateRank : mine?.profitRank;
  const podium = ranks.length >= 3;

  return (
    <section aria-label="판 랭킹" className={`flex min-w-0 flex-col gap-2.5 rounded-[20px] ${surfaceOf(inDrawer)}`}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-[15px] font-bold">
          랭킹 <span className="num font-normal text-zinc-500">{round.players}명</span>
        </span>
        <div role="tablist" aria-label="랭킹 기준" className="flex rounded-xl bg-zinc-800 p-0.5 text-xs">
          {(
            [
              ["수익금", false],
              ["수익률", true],
            ] as const
          ).map(([label, rate]) => (
            <button
              key={label}
              type="button"
              role="tab"
              aria-selected={byRate === rate}
              onClick={() => setByRate(rate)}
              className={`rounded-lg px-3 py-1.5 transition-colors ${byRate === rate ? "bg-elevated font-medium text-zinc-100" : "text-zinc-500 hover:text-zinc-300"}`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {ranks.length === 0 ? (
        <span className="py-6 text-center text-[13px] text-zinc-500">{round.settled ? "이 판엔 참여한 사람이 없어요." : "09:05에 랭킹이 확정돼요."}</span>
      ) : (
        <>
          {/* 시상대 — 2 · 1 · 3 순서로 세워 1위가 가운데에서 가장 높다 */}
          {podium && (
            <div className="mt-1 grid grid-cols-3 items-end gap-1.5">
              {[1, 0, 2].map((i) => {
                const r = ranks[i];
                return (
                  <div key={r.nickname + i} className="flex min-w-0 flex-col items-center gap-1.5 text-center">
                    <span className="max-w-full truncate text-[13px] font-bold">{r.nickname}</span>
                    <span className={`num text-[13px] font-bold ${r.pnl >= 0 ? "text-red-400" : "text-blue-400"}`}>{value(r)}</span>
                    <span
                      className="flex w-full flex-col items-center justify-center gap-0.5 rounded-b-[4px] rounded-t-xl"
                      style={{ height: PODIUM_HEIGHT[i], background: MEDAL[i], color: MEDAL_INK[i] }}
                    >
                      <span className="num text-xl font-bold">{r.rank}</span>
                      <span className="max-w-[90%] truncate text-[11px] opacity-80">{r.stockName}</span>
                    </span>
                  </div>
                );
              })}
            </div>
          )}
          <ol className="m-0 flex list-none flex-col p-0">
            {ranks.slice(podium ? 3 : 0).map((r, i) => (
              <li key={r.nickname + i} className={`grid grid-cols-[1.5rem_minmax(0,1fr)_auto] items-center gap-x-3 border-b px-0.5 py-2.5 ${inDrawer ? "border-zinc-800" : "border-zinc-850"}`}>
                <span className="num text-[13px] font-bold text-zinc-500">{r.rank}</span>
                <span className="min-w-0 truncate text-sm">
                  {r.nickname} <span className="text-xs text-zinc-500">· {r.stockName}</span>
                </span>
                <span className={`num text-sm font-semibold ${r.pnl >= 0 ? "text-red-400" : "text-blue-400"}`}>{value(r)}</span>
              </li>
            ))}
          </ol>
        </>
      )}

      {/* 08:05~09:05엔 NXT 종목에 건 사람만 올라 있다 — 1위가 바뀔 수 있다고 미리 말한다 */}
      {!round.settled && ranks.length > 0 && <span className="text-xs text-zinc-500">중간 순위예요. KRX 종목이 09:05에 팔리면 확정돼요.</span>}

      {me && (
        <div className={`grid grid-cols-[1.5rem_minmax(0,1fr)_auto] items-center gap-x-3 rounded-xl px-2 py-2.5 ${inDrawer ? "bg-zinc-800" : "bg-zinc-850"}`}>
          <span className="num text-center text-xs font-bold text-blue-700">{myRank ?? "–"}</span>
          <span className="flex min-w-0 flex-col gap-px">
            <NicknameEdit me={me} />
            <span className="text-xs text-zinc-500">{mine?.stockName ?? "이 판엔 안 걸었어요"}</span>
          </span>
          {mine && mine.pnl !== null && mine.rate !== null ? (
            <span className="flex flex-col items-end gap-px">
              <span className={`num text-sm font-semibold ${mine.pnl >= 0 ? "text-red-400" : "text-blue-400"}`}>{value({ pnl: mine.pnl, rate: mine.rate })}</span>
              <span className="num text-[11px] text-zinc-500">{byRate ? signedMan(mine.pnl) : pct(mine.rate)}</span>
            </span>
          ) : (
            <span />
          )}
        </div>
      )}
    </section>
  );
}

/**
 * 지난 판 — 복기가 주인공. 내 결과 · 랭킹을 위에 두고, 그 아래 "아, 이거 종베할걸"에
 * 결과순 종목 목록(페이지가 `children`으로 넣는다)을 둔다.
 */
export default function YesterdayReview({ round, me, member, children }: { round: ClosingBetRound; me: Me | null; member: boolean; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-6">
      <div className="grid grid-cols-1 items-stretch gap-4 xl:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
        <MyResultCard round={round} member={member} />
        <RankingCard round={round} me={me} />
      </div>

      <section aria-label="아, 이거 종베할걸" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-baseline justify-between gap-1.5">
          <h2 className="m-0 text-lg font-bold">아, 이거 종베할걸</h2>
          <span className="text-[13px] text-zinc-400">{md(round.day)} 20:00 종가에 사서 다음 날 아침에 팔았다면 · 오른 순</span>
        </div>
        {children}
      </section>
    </div>
  );
}
