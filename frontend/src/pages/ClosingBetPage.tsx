import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import PoolPanel from "../components/closingbet/PoolPanel";
import MarketFlowCard from "../components/closingbet/MarketFlowCard";
import StockList, { type StockRow } from "../components/closingbet/StockList";
import CheckPager from "../components/closingbet/CheckPager";
import BetTicket from "../components/closingbet/BetTicket";
import YesterdayReview from "../components/closingbet/YesterdayReview";
import DayStrip from "../components/closingbet/DayStrip";
import LastRound from "../components/closingbet/LastRound";
import { MOMENTS, countdown, momentAt, momentEnd, nextOpenLabel, type Moment } from "../components/closingbet/moment";
import {
  FEED,
  MARKET,
  NIGHT_FUTURES,
  POT_TODAY,
  POT_YESTERDAY,
  TODAY,
  YESTERDAY,
  checksOf,
  gradeOf,
  MY_YESTERDAY,
  nxtListed,
} from "../components/closingbet/mock";

/**
 * 모의 종가베팅 — 시간대가 화면을 정한다.
 *  복기 09:05~15:00 · 베팅 15:00~20:00 · 대기 20:00~08:00 · 결과 08:00~09:05 · 휴장
 *
 * 지금은 화면만 있다. 값은 mock.ts의 예시 데이터이고, 베팅·랭킹은 이 페이지 안에서만 산다.
 * 화면 확인용으로 `?moment=review|bet|night|result|holiday`가 시각을 덮어쓴다.
 */

const FEED_EVERY_MS = 2400;
/** 결과 발표 — 종목 하나씩 공개되는 간격 */
const REVEAL_EVERY_MS = 900;

const SUB: Record<Moment, (now: Date) => string> = {
  review: () => "오늘 판은 15:00에 열려요.",
  bet: () => "20:00 종가로 체결돼요. 그 전까지는 바꾸거나 취소할 수 있어요.",
  night: () => "야간선물이 밤새 움직여요. 08:05에 NXT 종목부터 결과가 나와요.",
  result: () => "08:05 NXT 종목 → 09:05 KRX 종목과 최종 랭킹",
  holiday: (now) => `다음 판은 ${nextOpenLabel(now)} 15:00에 열려요. 직전 판을 돌아봐요.`,
};

/** 결과 발표 순서 — NXT 종목 먼저(08:05), 나머지는 09:05 */
const REVEAL_ORDER = [...YESTERDAY.filter((s) => nxtListed(s.code)), ...YESTERDAY.filter((s) => !nxtListed(s.code))].map((s) => s.code);

export default function ClosingBetPage() {
  const [params] = useSearchParams();
  const [pick, setPick] = useState<string | null>(null);
  const [amount, setAmount] = useState(2000);
  const [confirmed, setConfirmed] = useState(false);
  const [feedIndex, setFeedIndex] = useState(0);
  const [now, setNow] = useState(() => new Date());
  const [detailYd, setDetailYd] = useState<string>("247540");
  const [revealed, setRevealed] = useState(0);
  const [nick, setNick] = useState("용감한수달");
  const [renamedAt, setRenamedAt] = useState<Date | null>(null);
  const me = {
    nick,
    renamedAt,
    rename: (v: string) => {
      setNick(v);
      setRenamedAt(new Date());
    },
  };

  const forced = params.get("moment") as Moment | null;
  const moment: Moment = forced && MOMENTS.includes(forced) ? forced : momentAt(now);

  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    const f = setInterval(() => setFeedIndex((i) => i + 1), FEED_EVERY_MS);
    return () => {
      clearInterval(t);
      clearInterval(f);
    };
  }, []);

  // 결과 발표 — 들어올 때마다 처음부터 하나씩 뒤집는다
  useEffect(() => {
    if (moment !== "result") return;
    setRevealed(0);
    const r = setInterval(() => setRevealed((n) => Math.min(REVEAL_ORDER.length, n + 1)), REVEAL_EVERY_MS);
    return () => clearInterval(r);
  }, [moment]);

  const isToday = moment === "bet" || moment === "night";
  const live = moment === "bet";
  const covered = moment === "result" ? new Set(REVEAL_ORDER.slice(revealed)) : undefined;
  // 대기 — 체결된 내 종베(이 화면에서 고른 게 없으면 예시)
  const betCode = live ? pick : (pick ?? MY_YESTERDAY.code);
  const list = isToday ? TODAY : YESTERDAY;
  const markets = MARKET[isToday ? "today" : "yesterday"];

  // 판돈 — 피드가 한 건씩 들어올 때마다 그 금액만큼(취소면 빠진다)
  const pots = useMemo(() => {
    const p = { ...(isToday ? POT_TODAY : POT_YESTERDAY) };
    if (live) for (let q = 0; q < feedIndex; q++) p[FEED[q % FEED.length].stock] += FEED[q % FEED.length].amount;
    return p;
  }, [isToday, live, feedIndex]);
  const totalMan = Object.values(pots).reduce((a, b) => a + b, 0);
  const feed = Array.from({ length: 5 }, (_, j) => FEED[(((feedIndex - 1 - j) % FEED.length) + FEED.length) % FEED.length]);

  const crowdTotal = list.reduce((a, s) => a + s.crowd, 0);
  const rows: StockRow[] = list.map((s) => {
    const n = checksOf(s, list, markets).filter((c) => c.ok).length;
    return { stock: s, grade: gradeOf(n), potMan: pots[s.name], contrarian: live && (s.crowd / crowdTotal) * 100 < 5 };
  });

  const [detailToday, setDetailToday] = useState<string | null>(null);
  const detailCode = live ? pick : isToday ? (detailToday ?? betCode) : detailYd;
  const detail = rows.find((r) => r.stock.code === detailCode) ?? null;
  const picked = isToday ? (rows.find((r) => r.stock.code === betCode) ?? null) : null;
  const ydStocks = useMemo(() => {
    const m = MARKET.yesterday;
    return YESTERDAY.map((s) => ({ stock: s, grade: gradeOf(checksOf(s, YESTERDAY, m).filter((c) => c.ok).length) }));
  }, []);

  const onPick = (code: string) => {
    if (!isToday) return setDetailYd(code);
    if (!live) return setDetailToday(code);
    // 내 픽을 다시 누르면 해제
    setConfirmed(false);
    setPick((p) => (p === code ? null : code));
  };

  return (
    <div className="mx-auto flex w-full max-w-[96rem] flex-col gap-6">
      <DayStrip moment={moment} sub={SUB[moment](now)} timer={countdown(momentEnd(moment, now), now)} />

      {isToday ? (
        <>
          <LastRound stocks={ydStocks} me={me} />
          <div className="grid grid-cols-1 items-stretch gap-4 xl:grid-cols-[minmax(0,0.78fr)_minmax(0,1.22fr)]">
            <PoolPanel totalMan={totalMan} players={live ? 128 + feedIndex : MY_YESTERDAY.players} feed={feed} closed={!live} />
            <MarketFlowCard
              title={live ? `오늘 시장 · ${now.getHours()}:${String(now.getMinutes()).padStart(2, "0")}` : "오늘 시장 · 마감"}
              flows={markets}
              afterLive={live}
              night={NIGHT_FUTURES.today}
            />
          </div>

          <div className="grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
            <section className="flex min-w-0 flex-col gap-3.5">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <h2 className="m-0 text-lg font-bold">오늘의 종목 {list.length}개</h2>
                <span className="text-[13px] text-zinc-400">{live ? "눌러서 내 픽으로 · 다시 누르면 해제" : "눌러서 등급 이유 보기"}</span>
              </div>
              <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.9fr)]">
                <StockList rows={rows} selectedCode={detailCode} mineCode={betCode} onPick={onPick} showResult={false} />
                <div className="lg:sticky lg:top-20">
                  {detail ? (
                    <CheckPager code={detail.stock.code} name={detail.stock.name} grade={detail.grade} checks={checksOf(detail.stock, list, markets)} />
                  ) : (
                    <div className="flex min-h-[240px] flex-col items-center justify-center gap-2 rounded-[18px] border-2 border-dashed border-zinc-800 p-6 text-center">
                      <span className="text-[15px] font-bold">종목을 누르면</span>
                      <span className="text-[13px] text-zinc-400">왜 그 등급인지 한 장씩 보여줘요.</span>
                    </div>
                  )}
                </div>
              </div>
              <p className="m-0 text-xs leading-relaxed text-zinc-500">등급은 종베 체크 다섯 가지(고가 마감 · 외국인 · 기관 · 시장 막판 · 신고가)를 몇 개 채웠는지로 매겨요. S는 다섯 개 모두.</p>
            </section>
            <aside className="xl:sticky xl:top-20">
              <BetTicket
                stock={picked?.stock ?? null}
                grade={picked?.grade ?? "C"}
                amount={amount}
                onAmount={setAmount}
                confirmed={live ? confirmed : true}
                filled={!live}
                onConfirm={() => setConfirmed(true)}
                onEdit={() => setConfirmed(false)}
                onCancel={() => {
                  setConfirmed(false);
                  setPick(null);
                }}
              />
            </aside>
          </div>
        </>
      ) : (
        <>
          <YesterdayReview stocks={rows.map((r) => ({ stock: r.stock, grade: r.grade }))} me={me} revealed={!covered?.has(MY_YESTERDAY.code)}>
            <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.9fr)]">
              <StockList rows={rows} selectedCode={detailYd} mineCode={MY_YESTERDAY.code} onPick={onPick} showResult covered={covered} />
              <div className="lg:sticky lg:top-20">
                {detail && <CheckPager code={detail.stock.code} name={detail.stock.name} grade={detail.grade} checks={checksOf(detail.stock, list, markets)} />}
              </div>
            </div>
          </YesterdayReview>
          <section aria-label="복기" className="flex flex-col gap-3">
            <div className="flex flex-wrap items-baseline justify-between gap-1.5">
              <h2 className="m-0 text-lg font-bold">복기 · 9/30 마감 때 시장</h2>
              <span className="text-[13px] text-zinc-400">20:00 체결 시점 그대로예요</span>
            </div>
            <MarketFlowCard title="어제 시장 · 9/30 마감" flows={markets} afterLive={false} night={NIGHT_FUTURES.yesterday} />
          </section>
        </>
      )}

      <p className="m-0 border-t border-zinc-850 pt-4 text-xs leading-relaxed text-zinc-500">
        종가베팅은 <b className="text-zinc-300">가상 금액으로 하는 모의 게임</b>이에요. 실제 주문이 나가지 않고, 돈을 걸거나 받지 않아요. 체결가와 매도가는 실제 시세로 계산하지만 수수료와 세금은 빼지 않았어요. 종목 등급과 체크는 참고용이며 투자 권유가 아니에요.
      </p>
    </div>
  );
}
