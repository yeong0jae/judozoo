import { eok, useCountUp, won } from "./parts";

export type FeedItem = { nick: string; stock: string; amount: number };

/**
 * 판돈 — 원 단위로 풀어 쓰고, 베팅이 들어올 때마다 숫자가 차오른다.
 * 참여 인원 · 마감까지 · 방금 들어온 베팅(LIVE)을 한 패널에 둔다.
 */
export default function PoolPanel({
  totalMan,
  players,
  feed,
  closed = false,
}: {
  /** 판돈 합계(만원) */
  totalMan: number;
  players: number;
  /** 최근 베팅, 맨 앞이 방금 */
  feed: FeedItem[];
  /** 20:00 마감 뒤 — 더 쌓이지 않고 LIVE도 멈춘다 */
  closed?: boolean;
}) {
  const shown = useCountUp(totalMan);

  return (
    <section aria-label="판돈" className="flex min-w-0 flex-col gap-2.5 rounded-3xl bg-zinc-900 px-[22px] py-5">
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1.5">
          <span className="flex items-center gap-2 text-[13px] font-semibold text-zinc-400">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <ellipse cx="12" cy="6" rx="8" ry="3" />
              <path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
            </svg>
            {closed ? "모의 판돈 · 마감" : "모의 판돈 · 지금 쌓이는 중"}
          </span>
          <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
            <span className="num whitespace-nowrap text-[40px] font-medium leading-tight tracking-tight text-zinc-100" aria-label={`모의 판돈 ${won(totalMan * 10000)}원`}>
              {won(Math.round(shown) * 10000)}
              <span className="ml-1 text-[22px] font-medium text-zinc-400">원</span>
            </span>
          </div>
        </div>
      </div>

      <span className="inline-flex items-center gap-2 self-start rounded-full bg-zinc-850 px-3.5 py-1.5 text-[13px]">
        <span className={`h-[7px] w-[7px] rounded-full ${closed ? "bg-zinc-500" : "animate-pulse bg-[#3182f6]"}`} />
        <span>
          <b className="num font-bold">{players}</b>
          {closed ? "명이 걸고 잠갔어요" : "명 참여 중"}
        </span>
      </span>

      {closed ? (
        <p className="m-0 mt-1 border-t border-zinc-800 pt-3 text-[13px] leading-relaxed text-zinc-400">내일 아침 이 돈의 운명이 정해져요. 08:05에 NXT 종목부터 결과가 나와요.</p>
      ) : (

      <div aria-live="polite" className="mt-1 flex flex-col gap-0.5 border-t border-zinc-800 pt-2.5">
        <span className="mb-1 inline-flex items-center gap-1.5 text-xs font-bold text-red-400">
          <span className="h-[7px] w-[7px] animate-pulse rounded-full bg-red-400" />
          LIVE <span className="font-medium text-zinc-500">방금 들어온 베팅</span>
        </span>
        {feed.map((f, i) => {
          const cancel = f.amount < 0;
          const allIn = f.amount >= 10000;
          return (
            <div
              key={`${i}-${f.nick}-${f.amount}`}
              className={`flex items-center justify-between gap-2.5 rounded-[10px] px-2.5 py-[7px] text-[13px] ${i === 0 ? "bg-[rgba(245,196,81,0.10)]" : ""}`}
              style={i === 0 ? undefined : { opacity: 1 - i * 0.16 }}
            >
              <span className="flex min-w-0 items-baseline gap-1.5">
                <b className="shrink-0 font-bold text-zinc-100">{f.nick}</b>
                <span className="truncate text-zinc-400">{f.stock}</span>
              </span>
              <span className="flex shrink-0 items-baseline gap-2">
                <b className={`num text-[13px] font-bold ${cancel ? "text-blue-400" : allIn ? "text-red-400" : "text-[#fcd34d]"}`}>
                  {cancel ? `취소 ${eok(-f.amount)}` : allIn ? "1억 올인" : eok(f.amount)}
                </b>
                <span className="num w-[3.4rem] text-right text-[11px] text-zinc-500">{i === 0 ? "방금" : `${i * 3}초 전`}</span>
              </span>
            </div>
          );
        })}
      </div>
      )}
    </section>
  );
}
