import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { useSearchParams } from "react-router-dom";
import { useLeaderTimeline, useMarketCalendarStatus } from "../api/queries";
import DateNavigator, { latestTradingDayStr, todayStr } from "../components/common/DateNavigator";
import Skeleton from "../components/common/Skeleton";
import InstagramIcon from "../components/common/InstagramIcon";
import { INSTAGRAM_URL } from "../lib/instagram";
import { overseasIsMain } from "../lib/marketSession";
import {
  MARKET_SPECS,
  arrangement,
  buildModel,
  displayOffset,
  entryAt,
  hhmm,
  isGap,
  lineupAt,
  nearestLive,
  sessionsOf,
  type Segment,
  type TimelineEvent,
  type TimelineMarket,
  type TimelineModel,
} from "../lib/leaderTimeline";

const ROW_H = 48;
const DIV_H = 24;
const RANKS = [1, 2, 3, 4, 5];
const SPEEDS = [0.5, 1, 2];
/** 1× = 하루(국내 12시간, 해외 16시간)를 30초 */
const tickMs = (slots: number, speed: number) => 30000 / slots / speed;

/** 좁은 화면 — 마우스가 없으니 세로선을 가운데 고정하고 띠를 밀어 시각을 옮긴다(`ScrubChart`) */
const NARROW = "(max-width: 767px)";
function useNarrow() {
  return useSyncExternalStore(
    (onChange) => {
      const mq = window.matchMedia(NARROW);
      mq.addEventListener("change", onChange);
      return () => mq.removeEventListener("change", onChange);
    },
    () => window.matchMedia(NARROW).matches,
  );
}

const MARKETS: { key: TimelineMarket; label: string }[] = [
  { key: "kr", label: "국내" },
  { key: "us", label: "해외" },
];

/** 그 시장의 현지 오늘(해외는 뉴욕 날짜)과 현지 분 */
function marketNow(market: TimelineMarket): { date: string; min: number } {
  if (market === "kr") {
    const d = new Date();
    return { date: todayStr(), min: d.getHours() * 60 + d.getMinutes() };
  }
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "America/New_York", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", hour12: false,
  }).formatToParts(new Date());
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "00";
  return { date: `${get("year")}-${get("month")}-${get("day")}`, min: (Number(get("hour")) % 24) * 60 + Number(get("minute")) };
}

/** 좁은 왼쪽 칸에 맞춘 짧은 표기 — "5.6조" / "3,005억" (시안과 같다) */
const krw = (v: number) => (v >= 1e12 ? `${(v / 1e12).toFixed(1)}조` : `${Math.round(v / 1e8).toLocaleString("ko-KR")}억`);
/** 한국 시각 기준 월/일 — 해외는 한국 자정을 넘으면 다음 날이 된다(뉴욕 9/25 장이 한국 9/26 새벽으로) */
function monthDay(date: string, kstMin: number) {
  const [y, m, d] = date.split("-").map(Number);
  const dt = new Date(y, m - 1, d + Math.floor(kstMin / 1440));
  return `${dt.getMonth() + 1}/${dt.getDate()}`;
}
const usd = (v: number) => (v >= 1e9 ? `$${(v / 1e9).toFixed(1)}B` : `$${(v / 1e6).toFixed(0)}M`);
const pct = (r: number) => `${r > 0 ? "+" : ""}${r.toFixed(2)}%`;
const fmtDur = (m: number) => (m >= 60 ? `${Math.floor(m / 60)}시간${m % 60 ? ` ${m % 60}분` : ""}` : `${m}분`);

/**
 * 주도주 타임라인 — 1분마다 찍은 홈 주도주 5의 하루 흐름.
 *
 * 줄 순서·배지·순위 변동은 3분 넘게 이어진 값(`lib/leaderTimeline`)을, 띠는 1분 원본을 쓴다.
 * 줄은 보고 있는 시각의 순위로 위아래로 미끄러진다 — 재생·클릭·← →·마우스로 훑을 때 모두.
 * 치수는 시안 px 값을 그대로 쓴다(024 캘린더와 같은 이유 — rem이면 넓은 화면에서 성겨진다).
 */
export default function LeaderTimelinePage() {
  // 홈 주도주 카드의 "타임라인"은 그 카드의 시장(`?market=kr|us`)으로 들어온다 — 처음 여는 탭만 정하고,
  // 그 뒤 탭 전환은 주소를 건드리지 않는다. 주소에 없으면 홈이 주인공을 고르는 규칙(`overseasIsMain`)을 따른다
  const [params] = useSearchParams();
  const asked = params.get("market");
  const [market, setMarket] = useState<TimelineMarket>(() =>
    asked === "kr" || asked === "us" ? asked : overseasIsMain(new Date()) ? "us" : "kr",
  );
  /** 탭을 주소나 클릭으로 정했는가 — 정했으면 휴장일이라도 해외로 돌리지 않는다 */
  const marketPicked = useRef(asked === "kr" || asked === "us");
  const [date, setDate] = useState(() => latestTradingDayStr(marketNow(market).date));
  /** 고정해 둔 슬롯. null = 최신을 따라간다 */
  const [pinned, setPinned] = useState<number | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
  const narrow = useNarrow();
  // 국내는 가장 최근 거래일로 연다 — 주말만 건너뛰면 추석 같은 휴장일이나 08:00 전엔 빈 화면이다(시그널과 같다).
  // 사용자가 날짜를 고른 뒤에는 건드리지 않는다
  const krCalendar = useMarketCalendarStatus("KR").data;
  const datePicked = useRef(false);
  useEffect(() => {
    if (market !== "kr" || datePicked.current || !krCalendar?.previousOpenDay) return;
    if (krCalendar.isHoliday || marketNow("kr").min < 480) setDate(krCalendar.previousOpenDay);
  }, [market, krCalendar]);
  // 추석 같은 국내 휴장일은 뒤늦게 알게 된다 — 시각만 보고 국내로 열었으면 해외로 돌린다(홈도 휴장을 받으면 순서를 바꾼다).
  // 처음 받았을 때 한 번만 본다 — 다시 받을 때마다 보면 보던 도중 20시를 넘길 때 탭이 저절로 바뀐다
  useEffect(() => {
    if (marketPicked.current || !krCalendar) return;
    marketPicked.current = true;
    if (!krCalendar.isHoliday) return;
    setMarket("us");
    setDate(latestTradingDayStr(marketNow("us").date));
  }, [krCalendar]);

  const now = marketNow(market);
  const spec0 = MARKET_SPECS[market];
  const live = date === now.date && now.min >= spec0.start && now.min < spec0.end;
  const { data, isLoading, isError } = useLeaderTimeline(market, date, live);
  const model = useMemo(() => (data ? buildModel(market, data) : null), [market, data]);
  const offset = displayOffset(market, date);

  const last = model?.last ?? -1;
  const cursor = pinned === null ? last : Math.min(pinned, last);
  /** 커서를 옮긴다. 마지막 분이면 고정하지 않고 최신을 따라가게 둔다 — 고정하면 새 분이 들어와도 그 분에 멈춘다 */
  const pin = (i: number) => setPinned(i >= last ? null : i);
  const shown = hover ?? cursor;

  // 재생 — 찍힌 분만 밟아 간다. 끝에 닿으면 멈춘다
  useEffect(() => {
    if (!playing || !model) return;
    const id = setInterval(() => {
      setPinned((p) => {
        const from = p ?? model.last;
        const next = model.live.find((i) => i > from);
        if (next === undefined) {
          setPlaying(false);
          return null;
        }
        return next;
      });
    }, tickMs(model.spec.slots, speed));
    return () => clearInterval(id);
  }, [playing, speed, model]);

  const changeMarket = (m: TimelineMarket) => {
    marketPicked.current = true;
    setMarket(m);
    setDate(latestTradingDayStr(marketNow(m).date));
    datePicked.current = false;
    setPinned(null);
    setPlaying(false);
  };
  const changeDate = (d: string) => {
    datePicked.current = true;
    setDate(d);
    setPinned(null);
    setPlaying(false);
  };
  const step = (dir: 1 | -1) => {
    if (!model) return;
    const list = dir > 0 ? model.live : [...model.live].reverse();
    const next = list.find((i) => (dir > 0 ? i > cursor : i < cursor));
    if (next !== undefined) pin(next);
  };
  const play = () => {
    if (!model) return;
    if (playing) return setPlaying(false);
    if (cursor >= last) setPinned(model.live[0] ?? 0);
    setPlaying(true);
  };
  const stop = () => {
    setPlaying(false);
    if (model) setPinned(model.live[0] ?? 0);
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        <div className="flex items-center gap-2.5">
          <h2 className="text-[20px] font-bold">주도주 타임라인</h2>
          {live && data?.lastTakenAt && <Ago iso={data.lastTakenAt} />}
          {/* 릴스가 이 화면으로 만든 영상이라, 여기 머무는 사람이 곧 팔로우할 사람이다 */}
          <a href={INSTAGRAM_URL} target="_blank" rel="noopener noreferrer" className="flex items-center gap-1 text-[13px] text-zinc-300 hover:text-zinc-100">
            <InstagramIcon size={14} />
            매일 릴스로 보기 ↗
          </a>
        </div>
        <div className="flex flex-wrap items-center gap-3 max-md:w-full max-md:justify-between">
          <div className="inline-flex rounded-[9px] bg-zinc-900 p-[3px]" role="group" aria-label="시장">
            {MARKETS.map((m) => (
              <button
                key={m.key}
                type="button"
                aria-pressed={market === m.key}
                onClick={() => changeMarket(m.key)}
                className={`h-[26px] px-[11px] rounded-[7px] text-[14px] font-medium transition-colors ${
                  market === m.key ? "bg-elevated text-zinc-100 shadow-sm" : "text-zinc-500 hover:text-zinc-300"
                }`}
              >
                {m.label}
              </button>
            ))}
          </div>
          <DateNavigator date={date} onChange={changeDate} />
        </div>
      </div>

      <div className="grid grid-cols-[minmax(0,1fr)] items-start gap-4 xl:grid-cols-[minmax(0,1fr)_400px]">
        <section className="overflow-hidden rounded-[14px] border border-zinc-800 text-[15px] leading-[normal]" aria-label="타임라인">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-800 px-[14px] py-[12px]">
            <div className="flex items-baseline gap-[10px]">
              {model && shown >= 0 && (
                <span className="num text-[24px] font-semibold text-zinc-500">{monthDay(date, model.spec.start + shown + offset)}</span>
              )}
              <b className="num text-[24px] font-semibold">{model && shown >= 0 ? hhmm(model.spec.start + shown + offset) : "--:--"}</b>
              <span className="text-[13px] text-zinc-500">
                {model && shown >= 0 &&
                  `${model.spec.sessionName(model.spec.start + shown)}${market === "us" ? ` · 뉴욕 ${hhmm(model.spec.start + shown)}` : ""}${live && shown === last ? " · 최신" : ""}`}
              </span>
            </div>
            <div className="flex items-center gap-2 max-md:w-full">
              {live && pinned !== null && cursor !== last && (
                // 폰은 재생 버튼들과 한 줄이라 "최신 →"으로 줄인다 — 꺾이면 버튼 안에서 화살표가 아래로 떨어진다
                <button type="button" onClick={() => setPinned(null)} className="h-[30px] shrink-0 whitespace-nowrap rounded-[8px] bg-[var(--tl-r4)] px-3 text-[14px] font-semibold text-[var(--tl-r4-fg)]">
                  최신<span className="max-md:hidden">으로</span> →
                </button>
              )}
              <div className="inline-flex rounded-[9px] bg-zinc-900 p-[3px] max-md:mr-auto" role="group" aria-label="재생 속도">
                {SPEEDS.map((sp) => (
                  <button
                    key={sp}
                    type="button"
                    aria-pressed={speed === sp}
                    onClick={() => setSpeed(sp)}
                    className={`h-[26px] px-[9px] rounded-[7px] text-[13px] font-medium ${
                      speed === sp ? "bg-elevated text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
                    }`}
                  >
                    {sp}×
                  </button>
                ))}
              </div>
              <button type="button" onClick={stop} aria-label="정지 — 처음으로" title="정지 — 처음으로" className="grid h-[30px] w-[30px] place-items-center rounded-[8px] bg-zinc-900 text-zinc-300 hover:bg-zinc-850">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden><rect x="5" y="5" width="14" height="14" rx="2" /></svg>
              </button>
              <button type="button" onClick={play} aria-pressed={playing} className="inline-flex h-[30px] items-center gap-[6px] rounded-[8px] bg-zinc-900 px-3 text-[14px] font-semibold text-zinc-300 hover:bg-zinc-850">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  {playing ? <path d="M8 5v14M16 5v14" /> : <path d="M7 5l12 7-12 7z" />}
                </svg>
                {playing ? "멈춤" : "재생"}
              </button>
            </div>
          </div>

          {isLoading ? (
            <div className="p-4"><Skeleton className="h-[320px] w-full" /></div>
          ) : isError ? (
            <p className="py-16 text-center text-[14px] text-zinc-500">타임라인을 불러오지 못했습니다</p>
          ) : !model || !model.live.length ? (
            <p className="py-16 text-center text-[14px] text-zinc-500">이 날은 기록이 없어요. 휴장이거나 기록을 시작하기 전이에요</p>
          ) : narrow ? (
            <ScrubChart
              model={model}
              market={market}
              offset={offset}
              live={live}
              cursor={cursor}
              onCursor={pin}
              onTouch={() => setPlaying(false)}
            />
          ) : (
            <Chart
              model={model}
              market={market}
              offset={offset}
              live={live}
              shown={shown}
              onHover={setHover}
              onPick={pin}
              onStep={step}
            />
          )}
        </section>

        <ChangeLog model={model} offset={offset} cursor={cursor} collapsible={narrow} onPick={(i) => { setPlaying(false); pin(i); }} />
      </div>
    </div>
  );
}

/** 마지막으로 찍은 뒤 몇 초 — 오늘의 주도주(PageHeader)와 같은 모양 */
function Ago({ iso }: { iso: string }) {
  const [, tick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, []);
  const sec = Math.max(0, Math.floor((Date.now() - new Date(`${iso}+09:00`).getTime()) / 1000));
  return (
    <span className="flex items-center gap-1.5 text-[13px] text-zinc-500">
      <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-accent" aria-hidden />
      {sec < 1 ? "방금" : sec < 60 ? `${sec}초 전` : `${Math.floor(sec / 60)}분 전`}
    </span>
  );
}

function Chart({
  model, market, offset, live, shown, onHover, onPick, onStep,
}: {
  model: TimelineModel;
  market: TimelineMarket;
  offset: number;
  live: boolean;
  shown: number;
  onHover: (i: number | null) => void;
  onPick: (i: number) => void;
  onStep: (dir: 1 | -1) => void;
}) {
  const hot = useRef<HTMLDivElement>(null);
  const [instant, setInstant] = useState(true);
  // 첫 배치는 애니메이션 없이 — 모든 줄이 맨 위에서 미끄러져 내려오지 않게
  useEffect(() => {
    setInstant(true);
    const id = requestAnimationFrame(() => requestAnimationFrame(() => setInstant(false)));
    return () => cancelAnimationFrame(id);
  }, [model]);

  const { spec, last } = model;
  const pctOf = (i: number) => `${(i / spec.slots) * 100}%`;
  const slotAt = (clientX: number) => {
    const r = hot.current!.getBoundingClientRect();
    const i = Math.floor(((clientX - r.left) / r.width) * spec.slots);
    return nearestLive(model, i); // 안 찍힌 분이면 그 앞 찍힌 분
  };

  const { top, rest, hidden } = arrangement(model, shown);
  const prevShown = model.live.filter((i) => i < shown).at(-1);
  const prevTop = prevShown === undefined ? null : lineupAt(model, prevShown);

  // 줄 위치
  const y = new Map<string, number>();
  let acc = 0;
  y.set("divTop", acc); acc += DIV_H;
  top.forEach((k) => { y.set(`k${k}`, acc); acc += ROW_H; });
  for (let n = top.length + 1; n <= 5; n++) { y.set(`ph${n}`, acc); acc += ROW_H; }
  y.set("divRest", acc); if (rest.length) acc += DIV_H;
  rest.forEach((k) => { y.set(`k${k}`, acc); acc += ROW_H; });
  hidden.forEach((k) => y.set(`k${k}`, acc));
  const boxH = acc;

  const topLabel = shown === last ? (live ? "지금 주도주 순위" : "마감 주도주 순위") : `${hhmm(spec.start + shown + offset)} 주도주 순위`;
  const sessions = sessionsOf(market, offset);
  const hours: number[] = [];
  for (let i = 0; i < spec.slots; i += 60) hours.push(i); // 0, 60, …, 마감 분
  const gapCols = [...Array(spec.slots).keys()].filter((i) => isGap(spec, i));

  return (
    // 세로는 자른다 — 숨긴 줄이 박스 바로 아래(`boxH`)에 한 줄 높이로 걸쳐 있어, 가로 스크롤만 열면
    // 브라우저가 세로도 auto로 바꿔 스크롤이 생긴다
    <div className="overflow-x-auto overflow-y-hidden">
      <div
        className="tl-chart relative min-w-[980px] outline-none"
        tabIndex={0}
        aria-label="시각별 주도주 순위. 좌우 화살표로 1분씩 이동"
        onKeyDown={(e) => {
          if (e.key === "ArrowRight") { e.preventDefault(); onStep(1); }
          if (e.key === "ArrowLeft") { e.preventDefault(); onStep(-1); }
        }}
      >
        {/* 시간축 · 세션 */}
        <div className="grid h-[36px] grid-cols-[var(--tl-label)_minmax(0,1fr)]">
          <div className="sticky left-0 z-[2] border-r border-zinc-800 bg-zinc-950" />
          <div className="relative">
            {hours.map((i) => (
              <span
                key={i}
                className="num absolute top-1/2 text-[12px] text-zinc-500"
                style={{ left: i === 0 ? 0 : pctOf(i + 0.5), transform: i === 0 ? "translate(2px,-50%)" : i >= spec.slots - 1 ? "translate(-100%,-50%)" : "translate(-50%,-50%)" }}
              >
                {hhmm(spec.start + i + offset).slice(0, 2)}
              </span>
            ))}
          </div>
        </div>
        <div className="grid h-[24px] grid-cols-[var(--tl-label)_minmax(0,1fr)]">
          <div className="sticky left-0 z-[2] border-r border-zinc-800 bg-zinc-950" />
          <div className="relative">
            {sessions.map(([a, b, label, regular]) => (
              <span
                key={label}
                className={`absolute inset-y-[4px] flex items-center justify-center overflow-hidden whitespace-nowrap rounded-[5px] text-[11.5px] font-bold ${
                  regular ? "bg-zinc-850 text-zinc-300" : "bg-zinc-900 text-zinc-500"
                }`}
                style={{ left: `calc(${pctOf(a - spec.start)} + 1px)`, width: `calc(${((b - a) / spec.slots) * 100}% - 2px)` }}
              >
                {label}
              </span>
            ))}
          </div>
        </div>

        {/* 종목 줄 */}
        <div className={`tl-rows relative ${instant ? "instant" : ""}`} style={{ height: boxH }}>
          <Divider y={y.get("divTop")!} label={topLabel} />
          <Divider y={y.get("divRest")!} label="주도주였던 종목" hidden={!rest.length} />
          {RANKS.filter((n) => n > top.length).map((n) => (
            <Placeholder key={`ph${n}`} n={n} y={y.get(`ph${n}`)!} none={top.length === 0 && n === 1} />
          ))}
          {model.order.map((k) => {
            const pos = top.indexOf(k);
            const e = pos >= 0 ? entryAt(model, k, shown) : null;
            const wasPos = prevTop ? prevTop.indexOf(k) : -2;
            const move = pos < 0 || !prevTop ? "" : wasPos < 0 ? "새로" : wasPos > pos ? `▲${wasPos - pos}` : wasPos < pos ? `▼${pos - wasPos}` : "";
            return (
              <StockRow
                key={k}
                y={y.get(`k${k}`)!}
                hidden={hidden.includes(k)}
                name={model.names[k]}
                meta={metaOf(model, k)}
                rank={pos >= 0 ? pos + 1 : null}
                move={move}
                rate={e?.rate ?? null}
                value={e ? (market === "kr" ? krw(e.value) : usd(e.value)) : null}
                gapLabel={model.snap[shown] ? "5위 밖" : "쉬는 구간"}
                segments={model.segs[k]}
                slots={spec.slots}
              />
            );
          })}
        </div>

        {/* 겹쳐 그리는 것들 — 쉬는 구간 빗금, 장 진행 중, 세로선, 마우스 영역 */}
        <div className="pointer-events-none absolute bottom-0 right-0 top-[60px] left-[var(--tl-label)]">
          {gapCols.length > 0 && gapRuns(gapCols).map(([a, b]) => (
            <div key={a} className="tl-hatch absolute inset-y-0" style={{ left: pctOf(a), width: `${((b - a) / spec.slots) * 100}%` }} />
          ))}
          {[...Array(11).keys()].map((h) => (
            <span key={h} className="absolute inset-y-0 w-px bg-zinc-800/40" style={{ left: pctOf((h + 1) * 60 + 0.5) }} />
          ))}
        </div>
        <div
          ref={hot}
          className="absolute bottom-0 right-0 top-0 left-[var(--tl-label)] cursor-crosshair"
          onMouseMove={(e) => onHover(slotAt(e.clientX))}
          onMouseLeave={() => onHover(null)}
          onClick={(e) => onPick(slotAt(e.clientX))}
        >
          {live && last < spec.slots - 1 && (
            <div className="tl-hatch absolute inset-y-0 right-0 border-l border-dashed border-zinc-600" style={{ left: pctOf(last + 1) }}>
              <span className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 whitespace-nowrap text-[14px] font-semibold text-zinc-500">
                장 진행 중
              </span>
            </div>
          )}
          <div className="pointer-events-none absolute inset-y-0 w-[2px] -ml-px bg-zinc-100/90" style={{ left: pctOf(shown + 0.5) }}>
            <b
              className="num absolute top-[2px] whitespace-nowrap rounded-[5px] bg-zinc-100 px-[6px] py-[2px] text-[12px] font-bold text-zinc-950"
              style={{ left: "50%", transform: shown > spec.slots - 40 ? "translateX(-100%)" : shown < 40 ? "none" : "translateX(-50%)" }}
            >
              {hhmm(spec.start + shown + offset)}
            </b>
          </div>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-[10px] border-t border-zinc-800 px-[14px] py-[10px] text-[13px] text-zinc-500">
        순위
        {RANKS.map((r) => (
          <span key={r} className="inline-flex items-center gap-[5px]">
            <i className="inline-block h-[10px] w-[14px] rounded-[3px]" style={{ background: `var(--tl-r${r})` }} />
            {r}위
          </span>
        ))}
      </div>
    </div>
  );
}

/** 모바일 치수 — 띠 1분 = 2px라 폰 한 화면에 두 시간쯤 보인다 */
const PX = 2;
const M_LABEL = 132;
const M_ROW = 46;
const M_AXIS = 30;

/**
 * 모바일 타임라인 — 세로선은 띠 영역 가운데에 고정하고, 띠를 옆으로 밀면 그 아래 시각이 커서가 된다.
 * 가로 스크롤을 그대로 쓰므로 관성도 따라온다. 재생·순위 변동·전체 막대가 커서를 옮기면 스크롤이 따라간다.
 */
function ScrubChart({
  model, market, offset, live, cursor, onCursor, onTouch,
}: {
  model: TimelineModel;
  market: TimelineMarket;
  offset: number;
  live: boolean;
  cursor: number;
  onCursor: (i: number) => void;
  /** 손을 대면 재생을 멈춘다 */
  onTouch: () => void;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  const [touched, setTouched] = useState(false);
  const [instant, setInstant] = useState(true);
  useLayoutEffect(() => {
    const el = scroller.current!;
    const ro = new ResizeObserver(() => setWidth(el.clientWidth));
    ro.observe(el);
    setWidth(el.clientWidth);
    return () => ro.disconnect();
  }, []);
  useEffect(() => {
    setInstant(true);
    const id = requestAnimationFrame(() => requestAnimationFrame(() => setInstant(false)));
    return () => cancelAnimationFrame(id);
  }, [model]);

  const { spec, last } = model;
  // 양 끝에 반 화면씩 비워 둬야 08:00과 20:00도 세로선 아래까지 온다
  const pad = Math.round(width / 2);
  // 장중에는 마지막 찍힌 분까지만 민다 — 하루 끝까지 열어 두면 세로선이 "장 진행 중" 빗금 위로 나간다.
  // 그 뒤는 오른쪽 반 화면 여백에 보이는 만큼만 그리고 잘라 낸다(잘라야 스크롤 폭이 늘지 않는다)
  const end = live ? last : spec.slots - 1;
  const x = (i: number) => pad + i * PX;
  /** 안 찍힌 분(쉬는 구간·장 진행 중)이면 그 앞 찍힌 분 */
  const snapTo = (i: number) => {
    return nearestLive(model, i);
  };
  const scrolledSlot = () => snapTo(Math.round(scroller.current!.scrollLeft / PX));

  // 커서 → 스크롤. 스크롤이 이미 그 분을 가리키면 두지 않는다(쉬는 구간에서 손가락과 다투지 않게)
  useLayoutEffect(() => {
    if (!width || scrolledSlot() === cursor) return;
    scroller.current!.scrollLeft = cursor * PX;
  }, [cursor, width, model]); // eslint-disable-line react-hooks/exhaustive-deps

  const hold = () => { onTouch(); setTouched(true); };

  const { top, rest, hidden } = arrangement(model, cursor);
  const y = new Map<string, number>();
  let acc = M_AXIS;
  top.forEach((k) => { y.set(`k${k}`, acc); acc += M_ROW; });
  for (let n = top.length + 1; n <= 5; n++) { y.set(`ph${n}`, acc); acc += M_ROW; }
  y.set("divRest", acc); if (rest.length) acc += DIV_H;
  rest.forEach((k) => { y.set(`k${k}`, acc); acc += M_ROW; });
  hidden.forEach((k) => y.set(`k${k}`, acc));
  const height = acc;
  const gapCols = [...Array(spec.slots).keys()].filter((i) => isGap(spec, i));
  const move = (key: string) => ({ transform: `translateY(${y.get(key)}px)` });

  return (
    <>
      <NowChanges model={model} offset={offset} cursor={cursor} />
      <MiniMap model={model} market={market} offset={offset} cursor={cursor} visible={width / PX} onCursor={(i) => { hold(); onCursor(snapTo(i)); }} />

      <div className={`tl-rows relative border-t border-zinc-800 ${instant ? "instant" : ""}`} style={{ height }}>
        {/* 왼쪽 종목 칸 — 띠와 같은 높이로 함께 미끄러진다 */}
        <div className="absolute inset-y-0 left-0 z-[2] border-r border-zinc-800 bg-zinc-950" style={{ width: M_LABEL }}>
          <div className="tl-row absolute inset-x-0 top-0 flex items-center px-[10px] text-[11.5px] font-bold text-zinc-500" style={{ ...move("divRest"), height: DIV_H, visibility: rest.length ? undefined : "hidden" }}>
            주도주였던 종목
          </div>
          {RANKS.filter((n) => n > top.length).map((n) => (
            <div key={`ph${n}`} className="tl-row absolute inset-x-0 top-0 flex items-center gap-[8px] border-t border-zinc-800 px-[10px]" style={{ ...move(`ph${n}`), height: M_ROW }}>
              <span className="num grid h-[22px] w-[22px] flex-none place-items-center rounded-[6px] text-[12px] font-bold text-zinc-600 shadow-[inset_0_0_0_1px_var(--color-zinc-800)]">{n}</span>
              <b className="truncate text-[13px] font-medium text-zinc-600">{top.length === 0 && n === 1 ? "주도주 없음" : "비어 있음"}</b>
            </div>
          ))}
          {model.order.map((k) => {
            const pos = top.indexOf(k);
            const e = pos >= 0 ? entryAt(model, k, cursor) : null;
            return (
              <div
                key={k}
                className={`tl-row absolute inset-x-0 top-0 flex min-w-0 items-center gap-[8px] border-t border-zinc-800 px-[10px] ${hidden.includes(k) ? "pointer-events-none opacity-0" : ""}`}
                style={{ ...move(`k${k}`), height: M_ROW }}
              >
                <span
                  className="num grid h-[22px] w-[22px] flex-none place-items-center rounded-[6px] text-[12px] font-bold"
                  style={pos < 0
                    ? { color: "var(--color-zinc-600)", boxShadow: "inset 0 0 0 1px var(--color-zinc-800)" }
                    : { background: `var(--tl-r${pos + 1})`, color: `var(--tl-r${pos + 1}-fg)` }}
                >
                  {pos < 0 ? "–" : pos + 1}
                </span>
                <div className="flex min-w-0 flex-col gap-[1px]">
                  <b className={`truncate text-[13.5px] font-semibold ${pos < 0 ? "text-zinc-600" : ""}`}>{model.names[k]}</b>
                  {pos >= 0 && e ? (
                    <small className={`num text-[11.5px] font-semibold ${e.rate >= 0 ? "text-red-600" : "text-blue-600"}`}>{pct(e.rate)}</small>
                  ) : (
                    <small className="text-[11.5px] text-zinc-600">{model.snap[cursor] ? "5위 밖" : "쉬는 구간"}</small>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        {/* 띠 — 가로 스크롤 */}
        <div
          ref={scroller}
          className="tl-scrub absolute inset-y-0 right-0 overflow-x-auto overflow-y-hidden"
          style={{ left: M_LABEL }}
          onScroll={() => { const i = scrolledSlot(); if (i !== cursor) onCursor(i); }}
          onPointerDown={hold}
          onTouchStart={hold}
          onWheel={hold}
        >
          <div className="relative h-full overflow-hidden" style={{ width: pad * 2 + end * PX }}>
            {[...Array((spec.slots - 1) / 60 + 1).keys()].map((h) => (
              <span key={h}>
                <span className="num absolute top-[8px] -translate-x-1/2 text-[11px] text-zinc-500" style={{ left: x(h * 60) }}>
                  {hhmm(spec.start + h * 60 + offset).slice(0, 2)}
                </span>
                <span className="absolute bottom-0 w-px bg-zinc-800/40" style={{ left: x(h * 60), top: M_AXIS }} />
              </span>
            ))}
            {gapRuns(gapCols).map(([a, b]) => (
              <div key={a} className="tl-hatch absolute bottom-0" style={{ top: M_AXIS, left: x(a), width: (b - a) * PX }} />
            ))}
            {live && last < spec.slots - 1 && (
              <div className="tl-hatch absolute bottom-0 grid place-items-center border-l border-dashed border-zinc-600" style={{ top: M_AXIS, left: x(last + 1), width: Math.min((spec.slots - 1 - last) * PX, pad - PX) }}>
                <span className="whitespace-nowrap text-[12px] font-semibold text-zinc-500">장 진행 중</span>
              </div>
            )}
            {model.order.map((k) => (
              <div
                key={k}
                className={`tl-row absolute inset-x-0 top-0 border-t border-zinc-800 ${hidden.includes(k) ? "opacity-0" : ""}`}
                style={{ ...move(`k${k}`), height: M_ROW }}
              >
                <PxLane segments={model.segs[k]} pad={pad} />
              </div>
            ))}
          </div>
        </div>

        {/* 가운데 고정 세로선 */}
        <div className="pointer-events-none absolute inset-y-0 z-[3]" style={{ left: M_LABEL + pad }}>
          <span className="absolute bottom-0 top-[24px] -ml-px w-[2px] bg-zinc-100/90" />
          <b className="num absolute top-[3px] -translate-x-1/2 whitespace-nowrap rounded-[5px] bg-zinc-100 px-[6px] py-[2px] text-[11.5px] font-bold text-zinc-950">
            {hhmm(spec.start + cursor + offset)}
          </b>
        </div>
        {!touched && (
          <span className="pointer-events-none absolute bottom-[10px] z-[4] -translate-x-1/2 whitespace-nowrap rounded-full bg-zinc-100 px-[10px] py-[6px] text-[12px] font-semibold text-zinc-950" style={{ left: M_LABEL + pad }}>
            옆으로 밀어 시각을 옮겨요
          </span>
        )}
      </div>
    </>
  );
}

/** 띠 한 줄(px) — 종목별로 한 번만 그린다 */
const PxLane = memo(function PxLane({ segments, pad }: { segments: Segment[]; pad: number }) {
  return (
    <>
      {segments.map((s) => (
        <i
          key={s.start}
          className="absolute top-1/2 h-[20px] -translate-y-1/2"
          style={{
            left: pad + s.start * PX,
            width: (s.end - s.start) * PX,
            background: `var(--tl-r${s.rank})`,
            borderRadius: `${s.roundStart ? 4 : 0}px ${s.roundEnd ? 4 : 0}px ${s.roundEnd ? 4 : 0}px ${s.roundStart ? 4 : 0}px`,
          }}
        />
      ))}
    </>
  );
});

/** 지금 분(또는 그 앞 가장 가까운 분)의 변화 — 모바일에선 순위 변동이 접혀 있어 커서 옆에 한 줄로 보여 준다 */
function NowChanges({ model, offset, cursor }: { model: TimelineModel; offset: number; cursor: number }) {
  const groups = useMemo(() => eventGroups(model), [model]);
  const group = groups.find(([i]) => i <= cursor);
  return (
    <div className="mx-[14px] mt-[12px] flex min-h-[40px] items-center gap-[8px] overflow-hidden rounded-[10px] bg-zinc-900 px-[10px] py-[6px]">
      {group ? (
        <>
          <span className="num flex-none text-[12px] text-zinc-500">{hhmm(model.spec.start + group[0] + offset)}</span>
          <div className="tl-scrub flex gap-[5px] overflow-x-auto">
            {group[1].map((e, n) => <EventChip key={n} e={e} names={model.names} />)}
          </div>
        </>
      ) : (
        <span className="text-[12.5px] text-zinc-500">아직 변화 없음</span>
      )}
    </div>
  );
}

/** 하루 전체 막대 — 지금 보이는 구간을 네모로. 누르거나 끌면 그 시각으로 간다 */
function MiniMap({
  model, market, offset, cursor, visible, onCursor,
}: {
  model: TimelineModel;
  market: TimelineMarket;
  offset: number;
  cursor: number;
  /** 띠 영역에 보이는 분 수 */
  visible: number;
  onCursor: (i: number) => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const { spec, last } = model;
  const jump = (clientX: number) => {
    const r = box.current!.getBoundingClientRect();
    onCursor(Math.round(((clientX - r.left) / r.width) * spec.slots));
  };
  const pctOf = (i: number) => `${(i / spec.slots) * 100}%`;
  return (
    <div className="px-[14px] pb-[8px] pt-[10px]">
      <div
        ref={box}
        className="relative h-[26px] cursor-pointer touch-none"
        onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); jump(e.clientX); }}
        onPointerMove={(e) => { if (e.currentTarget.hasPointerCapture(e.pointerId)) jump(e.clientX); }}
      >
        <div className="absolute inset-x-0 inset-y-[8px] overflow-hidden rounded-[5px] bg-zinc-900">
          {sessionsOf(market, offset).map(([a, b]) => (
            <span key={a} className="absolute inset-y-0 bg-zinc-800" style={{ left: pctOf(a - spec.start), width: pctOf(b - a) }} />
          ))}
          {last < spec.slots - 1 && <span className="tl-hatch absolute inset-y-0 right-0" style={{ left: pctOf(last + 1) }} />}
        </div>
        <span
          className="pointer-events-none absolute inset-y-[3px] rounded-[6px] border-[1.5px] border-zinc-100/60"
          style={{ left: pctOf(Math.max(0, Math.min(spec.slots - visible, cursor - visible / 2))), width: pctOf(Math.min(spec.slots, visible)) }}
        />
      </div>
      <div className="num flex justify-between text-[10.5px] text-zinc-500">
        {[0, 1, 2, 3].map((n) => Math.round(((spec.slots - 1) * n) / 3)).map((i) => <span key={i}>{hhmm(spec.start + i + offset).slice(0, 2)}</span>)}
      </div>
    </div>
  );
}

function gapRuns(cols: number[]): [number, number][] {
  const out: [number, number][] = [];
  cols.forEach((i) => {
    const lastRun = out.at(-1);
    if (lastRun && lastRun[1] === i) lastRun[1] = i + 1;
    else out.push([i, i + 1]);
  });
  return out;
}

function metaOf(model: TimelineModel, k: number) {
  const r = model.rankAt[k];
  const minutes = r.filter((x) => x > 0).length;
  const best = Math.min(...r.filter((x) => x > 0));
  return `${fmtDur(minutes)} · 최고 ${best}위`;
}

function Divider({ y, label, hidden = false }: { y: number; label: string; hidden?: boolean }) {
  return (
    <div
      className="tl-row absolute inset-x-0 top-0 grid h-[24px] grid-cols-[var(--tl-label)_minmax(0,1fr)]"
      style={{ transform: `translateY(${y}px)`, visibility: hidden ? "hidden" : undefined }}
    >
      <div className="sticky left-0 z-[2] flex items-center border-r border-zinc-800 bg-zinc-950 px-[12px] text-[11.5px] font-bold text-zinc-500">
        {label}
      </div>
      <div />
    </div>
  );
}

function Placeholder({ n, y, none }: { n: number; y: number; none: boolean }) {
  return (
    <div
      className="tl-row absolute inset-x-0 top-0 grid h-[48px] grid-cols-[var(--tl-label)_minmax(0,1fr)] border-t border-zinc-800"
      style={{ transform: `translateY(${y}px)` }}
    >
      <div className="sticky left-0 z-[2] flex items-center gap-[9px] border-r border-zinc-800 bg-zinc-950 px-[12px]">
        <span className="num grid h-[24px] w-[24px] flex-none place-items-center rounded-[6px] text-[13px] font-bold text-zinc-600 shadow-[inset_0_0_0_1px_var(--color-zinc-800)]">
          {n}
        </span>
        <div className="flex min-w-0 flex-col gap-[2px]">
          <b className="truncate text-[14px] font-medium text-zinc-600">{none ? "주도주 없음" : "비어 있음"}</b>
          {none && <small className="truncate text-[11.5px] text-zinc-600">주도주에 오른 종목이 없어요</small>}
        </div>
      </div>
      <div />
    </div>
  );
}

/** 띠는 종목별로 한 번만 그린다 — 마우스를 움직일 때마다 700칸을 다시 그리지 않게 */
const Lane = memo(function Lane({ segments, slots }: { segments: Segment[]; slots: number }) {
  return (
    <div className="relative h-full">
      {segments.map((s) => (
        <i
          key={s.start}
          className="absolute top-1/2 h-[22px] -translate-y-1/2"
          style={{
            left: `${(s.start / slots) * 100}%`,
            width: `${((s.end - s.start) / slots) * 100}%`,
            background: `var(--tl-r${s.rank})`,
            borderTopLeftRadius: s.roundStart ? 4 : 0,
            borderBottomLeftRadius: s.roundStart ? 4 : 0,
            borderTopRightRadius: s.roundEnd ? 4 : 0,
            borderBottomRightRadius: s.roundEnd ? 4 : 0,
          }}
        />
      ))}
    </div>
  );
});

function StockRow({
  y, hidden, name, meta, rank, move, rate, value, gapLabel, segments, slots,
}: {
  y: number;
  hidden: boolean;
  name: string;
  meta: string;
  rank: number | null;
  move: string;
  rate: number | null;
  value: string | null;
  gapLabel: string;
  segments: Segment[];
  slots: number;
}) {
  const out = rank === null;
  return (
    <div
      className={`tl-row absolute inset-x-0 top-0 grid h-[48px] grid-cols-[var(--tl-label)_minmax(0,1fr)] border-t border-zinc-800 ${hidden ? "pointer-events-none opacity-0" : ""}`}
      style={{ transform: `translateY(${y}px)` }}
    >
      <div className="sticky left-0 z-[2] flex min-w-0 items-center gap-[9px] border-r border-zinc-800 bg-zinc-950 px-[12px]">
        <span
          className="num grid h-[24px] w-[24px] flex-none place-items-center rounded-[6px] text-[13px] font-bold"
          style={out
            ? { color: "var(--color-zinc-600)", boxShadow: "inset 0 0 0 1px var(--color-zinc-800)" }
            : { background: `var(--tl-r${rank})`, color: `var(--tl-r${rank}-fg)` }}
        >
          {out ? "–" : rank}
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-[2px]">
          <b className={`flex items-center gap-[5px] truncate text-[14px] font-semibold ${out ? "text-zinc-600" : ""}`}>
            <span className="truncate">{name}</span>
            {move && (
              <i
                className="flex-none rounded-[4px] px-[4px] text-[11px] font-bold not-italic"
                style={move === "새로"
                  ? { color: "var(--tl-new)", background: "var(--tl-new-bg)" }
                  : { color: "var(--color-zinc-300)", background: "var(--color-zinc-850)" }}
              >
                {move}
              </i>
            )}
          </b>
          <small className={`num truncate text-[11.5px] max-sm:hidden ${out ? "text-zinc-600" : "text-zinc-500"}`}>{meta}</small>
        </div>
        <div className="num flex flex-none flex-col items-end gap-[2px] text-[12.5px]">
          {out ? (
            <span className="text-[12px] text-zinc-600">{gapLabel}</span>
          ) : (
            <>
              {rate !== null && <span className={`font-semibold ${rate >= 0 ? "text-red-600" : "text-blue-600"}`}>{pct(rate)}</span>}
              {value && <span className="text-[11.5px] text-zinc-500 max-sm:hidden">{value}</span>}
            </>
          )}
        </div>
      </div>
      <Lane segments={segments} slots={slots} />
    </div>
  );
}

/** 같은 분에 일어난 변화는 한 묶음으로 — 14:35에 여섯 줄이 흩어지면 무슨 일이 한꺼번에 있었는지 안 보인다. 최신이 위 */
function eventGroups(model: TimelineModel | null): [number, TimelineEvent[]][] {
  const byMin = new Map<number, TimelineEvent[]>();
  (model?.events ?? []).forEach((e) => byMin.set(e.i, [...(byMin.get(e.i) ?? []), e]));
  return [...byMin.entries()].sort((a, b) => b[0] - a[0]);
}

function EventChip({ e, names }: { e: TimelineEvent; names: string[] }) {
  const name = (k: number) => names[k] ?? "";
  if (e.type === "in")
    return (
      <span className="inline-flex flex-none items-center gap-[5px] whitespace-nowrap rounded-[6px] px-[7px] py-[3px] text-[12.5px] font-semibold" style={{ color: "var(--tl-new)", background: "var(--tl-new-bg)" }}>
        ＋ {name(e.k)}
        <i className="num grid h-[16px] min-w-[16px] place-items-center rounded-[4px] px-[3px] text-[11px] font-bold not-italic" style={{ background: `var(--tl-r${e.r})`, color: `var(--tl-r${e.r}-fg)` }}>{e.r}</i>
      </span>
    );
  if (e.type === "out")
    return <span className="inline-flex flex-none items-center whitespace-nowrap rounded-[6px] bg-zinc-900 px-[7px] py-[3px] text-[12.5px] text-zinc-500">－ {name(e.k)}</span>;
  if (e.type === "top")
    return (
      <span className="inline-flex flex-none items-center gap-[5px] whitespace-nowrap rounded-[6px] bg-zinc-900 px-[7px] py-[3px] text-[12.5px] font-bold">
        <i className="num grid h-[16px] w-[16px] place-items-center rounded-[4px] text-[11px] not-italic" style={{ background: "var(--tl-r1)", color: "var(--tl-r1-fg)" }}>1</i>
        {name(e.k)} 1위로
      </span>
    );
  return <span className="inline-flex flex-none items-center whitespace-nowrap rounded-[6px] bg-zinc-900 px-[7px] py-[3px] text-[12.5px] text-zinc-500">주도주 없음</span>;
}

function ChangeLog({
  model, offset, cursor, onPick, collapsible = false,
}: {
  model: TimelineModel | null;
  offset: number;
  cursor: number;
  onPick: (i: number) => void;
  /** 좁은 화면 — 타임라인 아래로 밀려 길게 늘어지지 않게 접어 둔다 */
  collapsible?: boolean;
}) {
  const groups = useMemo(() => eventGroups(model), [model]);
  // 보고 있는 시각의 직전(또는 그 시각) 묶음을 강조하고, 목록을 그리로 스크롤한다.
  // scrollIntoView는 페이지까지 끌고 가서(모바일에서 화면이 튄다) 목록의 scrollTop만 옮긴다
  const active = groups.find(([i]) => i <= cursor)?.[0];
  const listRef = useRef<HTMLOListElement>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const list = listRef.current;
    const item = list?.querySelector<HTMLElement>(`[data-i="${active}"]`);
    if (!list || !item) return;
    if (item.offsetTop < list.scrollTop) list.scrollTop = item.offsetTop;
    else if (item.offsetTop + item.offsetHeight > list.scrollTop + list.clientHeight) list.scrollTop = item.offsetTop + item.offsetHeight - list.clientHeight;
  }, [active, open]);
  const shown = !collapsible || open;

  return (
    <section className="flex max-h-[680px] flex-col rounded-[14px] border border-zinc-800 px-[16px] py-[14px] text-[14px] leading-[normal]">
      {collapsible ? (
        <button type="button" onClick={() => setOpen((o) => !o)} aria-expanded={open} className={`flex items-center justify-between text-left ${open ? "mb-[10px]" : ""}`}>
          <h3 className="text-[16px] font-bold">순위 변동</h3>
          <span className="text-[13px] text-zinc-500">{open ? "접기" : "펼치기"}</span>
        </button>
      ) : (
        <h3 className="mb-[10px] text-[16px] font-bold">순위 변동</h3>
      )}
      {model && shown && (
        <ol ref={listRef} className={`relative flex flex-col overflow-y-auto overflow-x-hidden ${collapsible ? "max-h-[360px]" : ""}`}>
          {groups.map(([i, evs], n) => (
            <li key={i} data-i={i} className="relative grid grid-cols-[48px_14px_minmax(0,1fr)] gap-x-[8px]">
              {/* 세로 선 — 묶음이 시간 흐름으로 이어져 보이게 */}
              <span aria-hidden className={`absolute left-[58px] w-px bg-zinc-800 ${n === 0 ? "top-[14px]" : "top-0"} ${n === groups.length - 1 ? "h-[14px]" : "bottom-0"}`} />
              <button type="button" onClick={() => onPick(i)} className={`num self-start pt-[7px] text-left text-[12.5px] ${i === active ? "font-bold text-zinc-100" : "text-zinc-500"}`}>
                {hhmm(model.spec.start + i + offset)}
              </button>
              <span aria-hidden className={`relative z-[1] mt-[12px] h-[8px] w-[8px] justify-self-center rounded-full ${i === active ? "bg-zinc-100" : "bg-zinc-700"}`} />
              <button
                type="button"
                onClick={() => onPick(i)}
                className={`mb-[4px] flex flex-wrap content-start gap-[5px] rounded-[8px] px-[6px] py-[5px] text-left hover:bg-zinc-850 ${i === active ? "bg-zinc-850" : ""}`}
              >
                {evs.map((e, n) => <EventChip key={n} e={e} names={model.names} />)}
              </button>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
