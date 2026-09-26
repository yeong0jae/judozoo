import { memo, useEffect, useMemo, useRef, useState } from "react";
import { useLeaderTimeline } from "../api/queries";
import DateNavigator, { latestTradingDayStr, todayStr } from "../components/common/DateNavigator";
import Skeleton from "../components/common/Skeleton";
import {
  SLOTS,
  arrangement,
  buildModel,
  displayOffset,
  entryAt,
  hhmm,
  isGap,
  lineupAt,
  sessionsOf,
  type Segment,
  type TimelineEvent,
  type TimelineMarket,
  type TimelineModel,
} from "../lib/leaderTimeline";

const ROW_H = 52;
const DIV_H = 26;
const RANKS = [1, 2, 3, 4, 5];
const SPEEDS = [0.5, 1, 2];
/** 1× = 하루(약 12시간)를 30초 */
const tickMs = (speed: number) => 30000 / SLOTS / speed;

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
 * 줄 순서·배지·변화 기록은 3분 넘게 이어진 값(`lib/leaderTimeline`)을, 띠는 1분 원본을 쓴다.
 * 줄은 보고 있는 시각의 순위로 위아래로 미끄러진다 — 재생·클릭·← →·마우스로 훑을 때 모두.
 * 치수는 시안 px 값을 그대로 쓴다(024 캘린더와 같은 이유 — rem이면 넓은 화면에서 성겨진다).
 */
export default function LeaderTimelinePage() {
  const [market, setMarket] = useState<TimelineMarket>("kr");
  const [date, setDate] = useState(() => latestTradingDayStr(marketNow("kr").date));
  /** 고정해 둔 슬롯. null = 최신을 따라간다 */
  const [pinned, setPinned] = useState<number | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);

  const now = marketNow(market);
  const spec0 = market === "kr" ? { start: 480, end: 1200 } : { start: 240, end: 960 };
  const live = date === now.date && now.min >= spec0.start && now.min < spec0.end;
  const { data, isLoading, isError } = useLeaderTimeline(market, date, live);
  const model = useMemo(() => (data ? buildModel(market, data) : null), [market, data]);
  const offset = displayOffset(market, date);

  const last = model?.last ?? -1;
  const cursor = pinned === null ? last : Math.min(pinned, last);
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
          return from;
        }
        return next;
      });
    }, tickMs(speed));
    return () => clearInterval(id);
  }, [playing, speed, model]);

  const changeMarket = (m: TimelineMarket) => {
    setMarket(m);
    setDate(latestTradingDayStr(marketNow(m).date));
    setPinned(null);
    setPlaying(false);
  };
  const changeDate = (d: string) => {
    setDate(d);
    setPinned(null);
    setPlaying(false);
  };
  const step = (dir: 1 | -1) => {
    if (!model) return;
    const list = dir > 0 ? model.live : [...model.live].reverse();
    const next = list.find((i) => (dir > 0 ? i > cursor : i < cursor));
    if (next !== undefined) setPinned(next);
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
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <div className="inline-flex rounded-[9px] bg-zinc-900 p-[3px]" role="group" aria-label="시장">
            {MARKETS.map((m) => (
              <button
                key={m.key}
                type="button"
                aria-pressed={market === m.key}
                onClick={() => changeMarket(m.key)}
                className={`h-[26px] px-[11px] rounded-[7px] text-[15px] font-medium transition-colors ${
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

      <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_320px]">
        <section className="overflow-hidden rounded-[14px] border border-zinc-800 text-[16px] leading-[normal]" aria-label="타임라인">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-800 px-[14px] py-[12px]">
            <div className="flex items-baseline gap-[10px]">
              {model && shown >= 0 && (
                <span className="num text-[26px] font-semibold text-zinc-500">{monthDay(date, model.spec.start + shown + offset)}</span>
              )}
              <b className="num text-[26px] font-semibold">{model && shown >= 0 ? hhmm(model.spec.start + shown + offset) : "--:--"}</b>
              <span className="text-[14px] text-zinc-500">
                {model && shown >= 0 &&
                  `${model.spec.sessionName(model.spec.start + shown)}${market === "us" ? ` · 뉴욕 ${hhmm(model.spec.start + shown)}` : ""}${live && shown === last ? " · 최신" : ""}`}
              </span>
            </div>
            <div className="flex items-center gap-2">
              {live && pinned !== null && cursor !== last && (
                <button type="button" onClick={() => setPinned(null)} className="h-[30px] rounded-[8px] bg-[var(--tl-r4)] px-3 text-[15px] font-semibold text-[var(--tl-r4-fg)]">
                  최신으로 →
                </button>
              )}
              <div className="inline-flex rounded-[9px] bg-zinc-900 p-[3px]" role="group" aria-label="재생 속도">
                {SPEEDS.map((sp) => (
                  <button
                    key={sp}
                    type="button"
                    aria-pressed={speed === sp}
                    onClick={() => setSpeed(sp)}
                    className={`h-[26px] px-[9px] rounded-[7px] text-[14px] font-medium ${
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
              <button type="button" onClick={play} aria-pressed={playing} className="inline-flex h-[30px] items-center gap-[6px] rounded-[8px] bg-zinc-900 px-3 text-[15px] font-semibold text-zinc-300 hover:bg-zinc-850">
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
            <p className="py-16 text-center text-[15px] text-zinc-500">타임라인을 불러오지 못했습니다</p>
          ) : !model || !model.live.length ? (
            <p className="py-16 text-center text-[15px] text-zinc-500">이 날은 기록이 없어요 — 휴장이거나 기록을 시작하기 전이에요</p>
          ) : (
            <Chart
              model={model}
              market={market}
              offset={offset}
              live={live}
              shown={shown}
              onHover={setHover}
              onPick={(i) => setPinned(i)}
              onStep={step}
            />
          )}
        </section>

        <ChangeLog model={model} offset={offset} cursor={cursor} onPick={(i) => { setPlaying(false); setPinned(i); }} />
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
    <span className="flex items-center gap-1.5 text-[14px] text-zinc-500">
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
  const pctOf = (i: number) => `${(i / SLOTS) * 100}%`;
  const slotAt = (clientX: number) => {
    const r = hot.current!.getBoundingClientRect();
    const i = Math.floor(((clientX - r.left) / r.width) * SLOTS);
    let j = Math.max(0, Math.min(last, i));
    while (j > 0 && !model.snap[j]) j--; // 안 찍힌 분이면 그 앞 찍힌 분
    return j;
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
  for (let i = 0; i < SLOTS; i += 60) hours.push(i); // 0, 60, …, 720(마감 분)
  const gapCols = [...Array(SLOTS).keys()].filter((i) => isGap(spec, i));

  return (
    <div className="overflow-x-auto">
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
        <div className="grid h-[38px] grid-cols-[var(--tl-label)_minmax(0,1fr)]">
          <div className="sticky left-0 z-[2] border-r border-zinc-800 bg-zinc-950" />
          <div className="relative">
            {hours.map((i) => (
              <span
                key={i}
                className="num absolute top-1/2 text-[13px] text-zinc-500"
                style={{ left: i === 0 ? 0 : pctOf(i + 0.5), transform: i === 0 ? "translate(2px,-50%)" : i >= SLOTS - 1 ? "translate(-100%,-50%)" : "translate(-50%,-50%)" }}
              >
                {hhmm(spec.start + i + offset).slice(0, 2)}
              </span>
            ))}
          </div>
        </div>
        <div className="grid h-[26px] grid-cols-[var(--tl-label)_minmax(0,1fr)]">
          <div className="sticky left-0 z-[2] border-r border-zinc-800 bg-zinc-950" />
          <div className="relative">
            {sessions.map(([a, b, label, regular]) => (
              <span
                key={label}
                className={`absolute inset-y-[4px] flex items-center justify-center overflow-hidden whitespace-nowrap rounded-[5px] text-[12.5px] font-bold ${
                  regular ? "bg-zinc-850 text-zinc-300" : "bg-zinc-900 text-zinc-500"
                }`}
                style={{ left: `calc(${pctOf(a - spec.start)} + 1px)`, width: `calc(${((b - a) / SLOTS) * 100}% - 2px)` }}
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
              />
            );
          })}
        </div>

        {/* 겹쳐 그리는 것들 — 쉬는 구간 빗금, 장 진행 중, 세로선, 마우스 영역 */}
        <div className="pointer-events-none absolute bottom-0 right-0 top-[64px] left-[var(--tl-label)]">
          {gapCols.length > 0 && gapRuns(gapCols).map(([a, b]) => (
            <div key={a} className="tl-hatch absolute inset-y-0" style={{ left: pctOf(a), width: `${((b - a) / SLOTS) * 100}%` }} />
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
          {live && last < SLOTS - 1 && (
            <div className="tl-hatch absolute inset-y-0 right-0 border-l border-dashed border-zinc-600" style={{ left: pctOf(last + 1) }}>
              <span className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 whitespace-nowrap text-[15px] font-semibold text-zinc-500">
                장 진행 중
              </span>
            </div>
          )}
          <div className="pointer-events-none absolute inset-y-0 w-[2px] -ml-px bg-zinc-100/90" style={{ left: pctOf(shown + 0.5) }}>
            <b
              className="num absolute top-[2px] whitespace-nowrap rounded-[5px] bg-zinc-100 px-[6px] py-[2px] text-[13px] font-bold text-zinc-950"
              style={{ left: "50%", transform: shown > SLOTS - 40 ? "translateX(-100%)" : shown < 40 ? "none" : "translateX(-50%)" }}
            >
              {hhmm(spec.start + shown + offset)}
            </b>
          </div>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-[10px] border-t border-zinc-800 px-[14px] py-[10px] text-[14px] text-zinc-500">
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
      className="tl-row absolute inset-x-0 top-0 grid h-[26px] grid-cols-[var(--tl-label)_minmax(0,1fr)]"
      style={{ transform: `translateY(${y}px)`, visibility: hidden ? "hidden" : undefined }}
    >
      <div className="sticky left-0 z-[2] flex items-center border-r border-zinc-800 bg-zinc-950 px-[12px] text-[12.5px] font-bold text-zinc-500">
        {label}
      </div>
      <div />
    </div>
  );
}

function Placeholder({ n, y, none }: { n: number; y: number; none: boolean }) {
  return (
    <div
      className="tl-row absolute inset-x-0 top-0 grid h-[52px] grid-cols-[var(--tl-label)_minmax(0,1fr)] border-t border-zinc-800"
      style={{ transform: `translateY(${y}px)` }}
    >
      <div className="sticky left-0 z-[2] flex items-center gap-[9px] border-r border-zinc-800 bg-zinc-950 px-[12px]">
        <span className="num grid h-[26px] w-[26px] flex-none place-items-center rounded-[6px] text-[14px] font-bold text-zinc-600 shadow-[inset_0_0_0_1px_var(--color-zinc-800)]">
          {n}
        </span>
        <div className="flex min-w-0 flex-col gap-[2px]">
          <b className="truncate text-[15px] font-medium text-zinc-600">{none ? "주도주 없음" : "비어 있음"}</b>
          {none && <small className="truncate text-[12.5px] text-zinc-600">주도주에 오른 종목이 없어요</small>}
        </div>
      </div>
      <div />
    </div>
  );
}

/** 띠는 종목별로 한 번만 그린다 — 마우스를 움직일 때마다 700칸을 다시 그리지 않게 */
const Lane = memo(function Lane({ segments }: { segments: Segment[] }) {
  return (
    <div className="relative h-full">
      {segments.map((s) => (
        <i
          key={s.start}
          className="absolute top-1/2 h-[24px] -translate-y-1/2"
          style={{
            left: `${(s.start / SLOTS) * 100}%`,
            width: `${((s.end - s.start) / SLOTS) * 100}%`,
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
  y, hidden, name, meta, rank, move, rate, value, gapLabel, segments,
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
}) {
  const out = rank === null;
  return (
    <div
      className={`tl-row absolute inset-x-0 top-0 grid h-[52px] grid-cols-[var(--tl-label)_minmax(0,1fr)] border-t border-zinc-800 ${hidden ? "pointer-events-none opacity-0" : ""}`}
      style={{ transform: `translateY(${y}px)` }}
    >
      <div className="sticky left-0 z-[2] flex min-w-0 items-center gap-[9px] border-r border-zinc-800 bg-zinc-950 px-[12px]">
        <span
          className="num grid h-[26px] w-[26px] flex-none place-items-center rounded-[6px] text-[14px] font-bold"
          style={out
            ? { color: "var(--color-zinc-600)", boxShadow: "inset 0 0 0 1px var(--color-zinc-800)" }
            : { background: `var(--tl-r${rank})`, color: `var(--tl-r${rank}-fg)` }}
        >
          {out ? "–" : rank}
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-[2px]">
          <b className={`flex items-center gap-[5px] truncate text-[15px] font-semibold ${out ? "text-zinc-600" : ""}`}>
            <span className="truncate">{name}</span>
            {move && (
              <i
                className="flex-none rounded-[4px] px-[4px] text-[12px] font-bold not-italic"
                style={move === "새로"
                  ? { color: "var(--tl-new)", background: "var(--tl-new-bg)" }
                  : { color: "var(--color-zinc-300)", background: "var(--color-zinc-850)" }}
              >
                {move}
              </i>
            )}
          </b>
          <small className={`num truncate text-[12.5px] max-sm:hidden ${out ? "text-zinc-600" : "text-zinc-500"}`}>{meta}</small>
        </div>
        <div className="num flex flex-none flex-col items-end gap-[2px] text-[13.5px]">
          {out ? (
            <span className="text-[13px] text-zinc-600">{gapLabel}</span>
          ) : (
            <>
              {rate !== null && <span className={`font-semibold ${rate >= 0 ? "text-red-600" : "text-blue-600"}`}>{pct(rate)}</span>}
              {value && <span className="text-[12.5px] text-zinc-500 max-sm:hidden">{value}</span>}
            </>
          )}
        </div>
      </div>
      <Lane segments={segments} />
    </div>
  );
}

function ChangeLog({
  model, offset, cursor, onPick,
}: {
  model: TimelineModel | null;
  offset: number;
  cursor: number;
  onPick: (i: number) => void;
}) {
  const name = (k: number) => model?.names[k] ?? "";
  const text = (e: TimelineEvent) =>
    e.type === "none" ? ["주도주 없음", "오른 종목 없음"]
      : e.type === "in" ? [name(e.k), `${e.r}위로 들어옴`]
        : e.type === "out" ? [name(e.k), "빠짐"]
          : [name(e.k), "1위로 올라섬"];
  const icon = (e: TimelineEvent) =>
    e.type === "in" ? { c: "+", style: { color: "var(--tl-new)", background: "var(--tl-new-bg)" } }
      : e.type === "top" ? { c: "1", style: { color: "var(--tl-r1-fg)", background: "var(--tl-r1)" } }
        : { c: e.type === "none" ? "0" : "−", style: { color: "var(--color-zinc-500)", background: "var(--color-zinc-850)" } };

  return (
    <section className="flex max-h-[620px] flex-col rounded-[14px] border border-zinc-800 px-[16px] py-[14px] text-[16px] leading-[normal]">
      <h3 className="mb-[10px] text-[17px] font-bold">변화 기록</h3>
      {!model ? null : (
        <ol className="flex flex-col overflow-y-auto">
          {[...model.events].reverse().map((e, n) => {
            const [b, small] = text(e);
            const ic = icon(e);
            return (
              <li key={`${e.i}-${e.type}-${n}`}>
                <button
                  type="button"
                  onClick={() => onPick(e.i)}
                  className={`-mx-[6px] grid w-[calc(100%+12px)] grid-cols-[44px_18px_minmax(0,1fr)] items-center gap-[8px] rounded-[8px] px-[6px] py-[7px] text-left text-[15px] hover:bg-zinc-850 ${
                    e.i === cursor ? "bg-zinc-850" : ""
                  }`}
                >
                  <span className="num text-[14px] text-zinc-500">{hhmm(model.spec.start + e.i + offset)}</span>
                  <span className="grid h-[18px] w-[18px] place-items-center rounded-[5px] text-[13px] font-bold" style={ic.style}>{ic.c}</span>
                  <span className="truncate"><b className="font-semibold">{b}</b> <small className="text-zinc-500">{small}</small></span>
                </button>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
