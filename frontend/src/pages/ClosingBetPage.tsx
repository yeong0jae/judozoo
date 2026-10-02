import { useEffect, useMemo, useRef, useState } from "react";
import PoolPanel from "../components/closingbet/PoolPanel";
import MarketFlowCard from "../components/closingbet/MarketFlowCard";
import StockList, { type StockRow } from "../components/closingbet/StockList";
import ClosingChecklist from "../components/closingbet/ClosingChecklist";
import BetTicket from "../components/closingbet/BetTicket";
import YesterdayReview, { type Me } from "../components/closingbet/YesterdayReview";
import DayStrip from "../components/closingbet/DayStrip";
import LastRound from "../components/closingbet/LastRound";
import { ago, countdown, dayLabel, type Moment } from "../components/closingbet/moment";
import { md } from "../components/closingbet/parts";
import { checksOf, withVerdict, type BetStock, type MarketFlow, type MarketName, type Verdict } from "../components/closingbet/model";
import {
  marketName,
  toBetStock,
  toFlows,
  useCancelBet,
  useClosingBetFeed,
  useClosingBetMarket,
  useClosingBetNow,
  useClosingBetRound,
  useClosingBetStocks,
  usePlaceBet,
  useRenameNickname,
  type ClosingBetMarketRaw,
  type ClosingBetNow,
} from "../api/closingbet";
import { goLogin, useMe } from "../api/auth";
import { ApiError } from "../api/client";

/**
 * 모의 종가베팅 — 시간대가 화면을 정한다(지금 칸은 서버가 알려 준다).
 *  복기 09:05~15:00 · 베팅 15:00~20:00 · 대기 20:00~08:00 · 결과 08:00~09:05 · 휴장
 */

const SUB: Record<Moment, (next: Date | null) => string> = {
  review: () => "오늘 판은 15:00에 열려요.",
  bet: () => "20:00 종가로 체결돼요. 그 전까지는 바꾸거나 취소할 수 있어요.",
  night: () => "야간선물이 밤새 움직여요. 08:05에 NXT 종목부터 결과가 나와요.",
  result: () => "08:05 NXT 종목 → 09:05 KRX 종목과 최종 랭킹",
  holiday: (next) => (next ? `다음 판은 ${dayLabel(next)}에 이어져요. 직전 판을 돌아봐요.` : "직전 판을 돌아봐요."),
};

function nightOf(raw: ClosingBetMarketRaw, note: string) {
  return raw.night ? { ...raw.night, note } : { price: 0, rate: 0, note: "야간선물 없음" };
}

function LoginBox({ title, body, className = "" }: { title: string; body: string; className?: string }) {
  return (
    <div className={`flex flex-col items-start justify-center gap-3 ${className}`}>
      <span className="text-[15px] font-bold">{title}</span>
      <span className="text-[13px] text-zinc-400">{body}</span>
      <button type="button" onClick={goLogin} className="h-9 rounded-xl bg-zinc-100 px-4 text-[13px] font-semibold text-zinc-950 hover:bg-white">
        로그인
      </button>
    </div>
  );
}

/** 등급 이유 카드 — 로그인 + 서버 판정 + 시장 수급이 있을 때만 */
function Pager({ row, flows, verdict, member }: { row: StockRow; flows: Record<MarketName, MarketFlow> | null; verdict: Verdict | null; member: boolean }) {
  const box = "min-h-[240px] rounded-[18px] border-2 border-dashed border-zinc-800 p-6";
  if (!member) {
    return <LoginBox className={box} title={`왜 ${row.grade ?? "그"} 등급일까?`} body="등급 이유(고가 마감·외인·기관·마감 부근 수급·구간 신고가)는 로그인 후 볼 수 있어요." />;
  }
  if (!verdict || !flows || !row.grade) {
    return <div className={`flex items-center justify-center text-[13px] text-zinc-400 ${box}`}>이 종목은 아직 등급을 매기지 못했어요.</div>;
  }
  return <ClosingChecklist code={row.stock.code} name={row.stock.name} grade={row.grade} checks={withVerdict(checksOf(row.stock, flows), verdict)} />;
}

function MarketLocked() {
  return (
    <section aria-label="시장 수급" className="rounded-3xl bg-zinc-900 px-6 py-[22px]">
      <LoginBox title="시장 수급" body="코스피·코스닥 현물·선물 외인·기관, 마감·애프터 수급과 야간선물은 로그인 후 볼 수 있어요." />
    </section>
  );
}

// --- 베팅 · 대기 -----------------------------------------------------------------

function TodayBoard({ now, clock, member, me }: { now: ClosingBetNow; clock: Date; member: boolean; me: Me | null }) {
  const moment = now.moment;
  const live = moment === "bet";
  const stocksQ = useClosingBetStocks(moment);
  const marketQ = useClosingBetMarket(moment, member);
  const feedQ = useClosingBetFeed(moment);
  const roundQ = useClosingBetRound(now.reviewDay, moment);
  const place = usePlaceBet();
  const cancel = useCancelBet();

  const myBet = now.me?.bet ?? null;
  const [pick, setPick] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [amount, setAmount] = useState(2000);
  const [detailCode, setDetailCode] = useState<string | null>(null);
  const [error, setError] = useState("");
  const synced = useRef(false);
  useEffect(() => {
    // 처음 받은 내 베팅 금액으로 슬라이더를 맞춘다(그 뒤로는 사용자가 움직인 값)
    if (myBet && !synced.current) {
      synced.current = true;
      setAmount(myBet.amountMan);
    }
  }, [myBet]);

  const flows = marketQ.data ? toFlows(marketQ.data) : null;
  const items = stocksQ.data ?? [];
  const list = items.map((s) => toBetStock(s, s.price, marketName(s.market)));
  const crowdTotal = items.reduce((a, s) => a + s.crowd, 0);
  const rows: StockRow[] = items.map((s, i) => ({
    stock: list[i],
    grade: s.grade,
    potMan: s.potMan,
    contrarian: live && crowdTotal >= 20 && (s.crowd / crowdTotal) * 100 < 5,
  }));

  const selected = live ? (pick ?? myBet?.stockCode ?? null) : (myBet?.stockCode ?? null);
  const confirmed = !!myBet && selected === myBet.stockCode && !editing;
  const ticketIdx = items.findIndex((s) => s.code === selected);
  // 목록에서 빠진 종목에 건 베팅 — 티켓은 내 베팅으로 그린다
  const ticketStock: BetStock | null =
    ticketIdx >= 0
      ? list[ticketIdx]
      : myBet && selected === myBet.stockCode
        ? { code: myBet.stockCode, name: myBet.stockName, market: "코스피", lead: false, price: myBet.buyPrice ?? 0, chg: 0, high: 0, low: 0, frgn: 0, inst: 0, frgn5: 0, inst5: 0, recentHighGap: null, crowd: 0 }
        : null;

  const shownDetail = detailCode ?? selected;
  const detailIdx = items.findIndex((s) => s.code === shownDetail);

  const onPick = (code: string) => {
    setDetailCode(code);
    if (!live || !member) return;
    setError("");
    if (code === selected && code !== myBet?.stockCode) {
      setPick(null); // 고르기만 한 종목을 다시 누르면 해제
      return;
    }
    setPick(code);
    setEditing(code !== myBet?.stockCode);
  };

  const fail = (e: unknown) => setError(e instanceof ApiError ? e.message : "처리하지 못했어요. 잠시 뒤 다시 해 주세요.");
  const feed = (feedQ.data ?? []).slice(0, 5).map((f) => ({ id: f.id, nick: f.nickname, stock: f.stockName, amount: f.amountMan, ago: ago(new Date(f.at), clock) }));

  return (
    <>
      {roundQ.data && <LastRound round={roundQ.data} me={me} member={member} />}
      <div className="grid grid-cols-1 items-stretch gap-4 xl:grid-cols-[minmax(0,0.78fr)_minmax(0,1.22fr)]">
        <PoolPanel totalMan={now.potMan} players={now.players} feed={feed} closed={!live} />
        {!member ? (
          <MarketLocked />
        ) : marketQ.data ? (
          <MarketFlowCard
            title={live ? "오늘 시장" : "오늘 시장 · 마감"}
            flows={toFlows(marketQ.data)}
            afterLive={live}
            night={nightOf(marketQ.data, "지금")}
          />
        ) : (
          <section className="flex items-center justify-center rounded-3xl bg-zinc-900 text-[13px] text-zinc-500">시장 수급을 불러오는 중…</section>
        )}
      </div>

      <div className="grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
        <section className="flex min-w-0 flex-col gap-3.5">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="m-0 text-lg font-bold">오늘의 종목 {items.length}개</h2>
            <span className="text-[13px] text-zinc-400">{live && member ? "눌러서 내 픽으로 · 다시 누르면 해제" : "눌러서 등급 이유 보기"}</span>
          </div>
          {stocksQ.isLoading ? (
            <div className="py-10 text-center text-[13px] text-zinc-500">종목을 불러오는 중…</div>
          ) : items.length === 0 ? (
            <div className="py-10 text-center text-[13px] text-zinc-500">지금 주도주·후보가 없어요.</div>
          ) : (
            <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.9fr)]">
              <StockList rows={rows} selectedCode={shownDetail} mineCode={selected} onPick={onPick} showResult={false} />
              <div className="lg:sticky lg:top-20">
                {detailIdx >= 0 ? (
                  <Pager row={rows[detailIdx]} flows={flows} verdict={items[detailIdx].checks} member={member} />
                ) : (
                  <div className="flex min-h-[240px] flex-col items-center justify-center gap-2 rounded-[18px] border-2 border-dashed border-zinc-800 p-6 text-center">
                    <span className="text-[15px] font-bold">종목을 누르면</span>
                    <span className="text-[13px] text-zinc-400">왜 그 등급인지 한 장씩 보여줘요.</span>
                  </div>
                )}
              </div>
            </div>
          )}
          <p className="m-0 text-xs leading-relaxed text-zinc-500">등급은 종베 체크 다섯 가지(고가 마감 · 외국인 · 기관 · 마감 부근 수급 · 구간 신고가)를 몇 개 채웠는지로 매겨요. S는 다섯 개 모두.</p>
        </section>
        <aside className="xl:sticky xl:top-20">
          <BetTicket
            stock={ticketStock}
            grade={ticketIdx >= 0 ? (items[ticketIdx].grade ?? "C") : "C"}
            amount={amount}
            onAmount={setAmount}
            confirmed={live ? confirmed : true}
            filled={!live}
            nxt={ticketIdx >= 0 ? items[ticketIdx].nxt : null}
            dayLabel={now.bettingDay ? md(now.bettingDay) : ""}
            buyPrice={myBet?.buyPrice}
            shares={myBet?.shares}
            voided={myBet?.status === "void"}
            member={member}
            busy={place.isPending || cancel.isPending}
            error={error}
            onConfirm={() => {
              if (!selected) return;
              setError("");
              place.mutate(
                { code: selected, amountMan: amount },
                {
                  onSuccess: () => {
                    setEditing(false);
                    setPick(null);
                  },
                  onError: fail,
                },
              );
            }}
            onEdit={() => setEditing(true)}
            onCancel={() => {
              setError("");
              cancel.mutate(undefined, {
                onSuccess: () => {
                  setPick(null);
                  setEditing(false);
                },
                onError: fail,
              });
            }}
          />
        </aside>
      </div>
    </>
  );
}

// --- 복기 · 결과 · 휴장 -------------------------------------------------------------

function ReviewBoard({ now, member, me }: { now: ClosingBetNow; member: boolean; me: Me | null }) {
  const roundQ = useClosingBetRound(now.reviewDay, now.moment);
  const [detailCode, setDetailCode] = useState<string | null>(null);
  const round = roundQ.data;

  const view = useMemo(() => {
    if (!round) return null;
    const list = round.stocks.map((s) => toBetStock(s, s.closePrice, marketName(s.market), s.rate));
    const rows: StockRow[] = round.stocks.map((s, i) => ({ stock: list[i], grade: s.grade, potMan: s.potMan, contrarian: false }));
    const covered = new Set(round.stocks.filter((s) => s.rate === null).map((s) => s.code));
    return { list, rows, covered };
  }, [round]);

  if (roundQ.isLoading) return <div className="py-16 text-center text-[13px] text-zinc-500">지난 판을 불러오는 중…</div>;
  if (!round || !view) return <div className="py-16 text-center text-[14px] text-zinc-400">아직 돌아볼 지난 판이 없어요. 15:00에 판이 열려요.</div>;

  const flows = round.market ? toFlows(round.market) : null;
  const shown = detailCode ?? round.me?.stockCode ?? round.stocks[0]?.code ?? null;
  const idx = round.stocks.findIndex((s) => s.code === shown);

  return (
    <>
      <YesterdayReview round={round} me={me} member={member}>
        <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.9fr)]">
          <StockList rows={view.rows} selectedCode={shown} mineCode={round.me?.stockCode ?? null} onPick={setDetailCode} showResult covered={view.covered} />
          <div className="lg:sticky lg:top-20">{idx >= 0 && <Pager row={view.rows[idx]} flows={flows} verdict={round.stocks[idx].checks} member={member} />}</div>
        </div>
      </YesterdayReview>
      <section aria-label="복기" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-baseline justify-between gap-1.5">
          <h2 className="m-0 text-lg font-bold">복기 · {md(round.day)} 마감 때 시장</h2>
          <span className="text-[13px] text-zinc-400">20:00 체결 시점 그대로예요</span>
        </div>
        {member && round.market ? (
          <MarketFlowCard title={`시장 · ${md(round.day)} 마감`} flows={toFlows(round.market)} afterLive={false} night={nightOf(round.market, `${md(round.day)} 20:00`)} />
        ) : (
          <MarketLocked />
        )}
      </section>
    </>
  );
}

// --- 페이지 -----------------------------------------------------------------------

export default function ClosingBetPage() {
  const nowQ = useClosingBetNow();
  const meQ = useMe();
  const member = !!meQ.data?.authenticated;
  const rename = useRenameNickname(nowQ.data?.reviewDay);
  const [clock, setClock] = useState(() => new Date());

  useEffect(() => {
    const t = setInterval(() => setClock(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  const n = nowQ.data;
  const nextAt = n?.nextAt ? new Date(n.nextAt) : null;
  // 다음 칸이 시작되면 바로 다시 묻는다(1분 폴링을 기다리지 않는다)
  const refetchedFor = useRef<string | null>(null);
  const { refetch } = nowQ;
  useEffect(() => {
    if (n?.nextAt && clock.getTime() >= new Date(n.nextAt).getTime() && refetchedFor.current !== n.nextAt) {
      refetchedFor.current = n.nextAt;
      void refetch();
    }
  }, [clock, n?.nextAt, refetch]);

  const me: Me | null =
    member && n?.me
      ? {
          nick: n.me.nickname,
          nextRenameAt: n.me.nextRenameAt ? new Date(n.me.nextRenameAt) : null,
          rename: (v: string) => rename.mutateAsync(v),
        }
      : null;

  return (
    <div className="mx-auto flex w-full max-w-[96rem] flex-col gap-6">
      {!n ? (
        <div className="py-20 text-center text-[13px] text-zinc-500">{nowQ.isError ? "종가베팅을 불러오지 못했어요. 잠시 뒤 다시 열어 주세요." : "불러오는 중…"}</div>
      ) : (
        <>
          <DayStrip moment={n.moment} sub={SUB[n.moment](nextAt)} timer={nextAt ? countdown(nextAt, clock) : "--:--:--"} />
          {n.moment === "bet" || n.moment === "night" ? <TodayBoard now={n} clock={clock} member={member} me={me} /> : <ReviewBoard now={n} member={member} me={me} />}
        </>
      )}

      <p className="m-0 border-t border-zinc-850 pt-4 text-xs leading-relaxed text-zinc-500">
        종가베팅은 <b className="text-zinc-300">가상 금액으로 하는 모의 게임</b>이에요. 실제 주문이 나가지 않고, 돈을 걸거나 받지 않아요. 체결가와 매도가는 실제 시세로 계산하지만 수수료와 세금은 빼지 않았어요. 종목 등급과 체크는 참고용이며 투자 권유가 아니에요.
      </p>
    </div>
  );
}
