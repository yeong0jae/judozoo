import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useLeaderCalendar } from "../api/queries";
import type { LeaderDayItem, LeaderStockItem } from "../types";
import { formatKoreanMoney } from "../lib/format";
import { todayStr } from "../components/common/DateNavigator";
import Skeleton from "../components/common/Skeleton";

type Mode = "both" | "domestic" | "overseas";

const MODE_KEY = "leaderCalendar.mode";
const MAX = 5;
const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

const MODES: { key: Mode; label: string; caption: string }[] = [
  { key: "both", label: "모두", caption: "국내장과 해외장(뉴욕 날짜) 마감 기준 주도주" },
  { key: "domestic", label: "국내", caption: "국내장 마감 기준 주도주" },
  { key: "overseas", label: "해외", caption: "해외장(뉴욕 날짜) 마감 기준 주도주" },
];

const pad = (n: number) => String(n).padStart(2, "0");
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = (s: string) => {
  const [y, m, d] = s.split("-").map(Number);
  return new Date(y, m - 1, d);
};

const pct = (rate: number) => `${rate > 0 ? "+" : ""}${rate.toFixed(1)}%`;

function compactUsd(v: number): string {
  if (v >= 1e9) return `$${(v / 1e9).toFixed(2)}B`;
  if (v >= 1e6) return `$${(v / 1e6).toFixed(1)}M`;
  return `$${Math.round(v).toLocaleString("en-US")}`;
}

const stockKey = (s: LeaderStockItem) => (s.exchange ? `${s.exchange}:${s.code}` : s.code);

function readMode(): Mode {
  try {
    const raw = localStorage.getItem(MODE_KEY);
    if (raw === "both" || raw === "domestic" || raw === "overseas") return raw;
  } catch {
  }
  return "both";
}

export default function LeaderCalendarPage() {
  const today = todayStr();
  const thisMonth = today.slice(0, 7);
  const [span, setSpan] = useState(2);
  const months = useMemo(() => monthsBack(thisMonth, span), [thisMonth, span]);
  const [visible, setVisible] = useState(thisMonth);
  const [mode, setModeState] = useState<Mode>(readMode);
  const [selected, setSelected] = useState<string | null>(null);
  const [focus, setFocus] = useState<string | null>(null);

  const scroller = useRef<HTMLDivElement>(null);
  const sections = useRef(new Map<string, HTMLDivElement>());
  const anchor = useRef<{ month: string } | null>(null);
  const touched = useRef(false);

  const oldestQ = useLeaderCalendar(months[0], months[0] === thisMonth);
  const exhausted = oldestQ.data !== undefined && oldestQ.data.domestic.length === 0 && oldestQ.data.overseas.length === 0;
  const shown = exhausted && months.length > 1 ? months.slice(1) : months;

  const visibleQ = useLeaderCalendar(visible, visible === thisMonth);
  const detailMonth = selected ? selected.slice(0, 7) : visible;
  const detailQ = useLeaderCalendar(detailMonth, detailMonth === thisMonth);

  const sectionTop = (month: string) => {
    const el = sections.current.get(month);
    return el ? el.offsetTop : null;
  };

  const settleTimer = useRef<number | undefined>(undefined);

  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const target = anchor.current?.month ?? (touched.current ? null : thisMonth);
    if (!target) return;
    const place = () => {
      const top = sectionTop(target);
      if (top !== null) el.scrollTop = top;
    };
    place();
    const ro = new ResizeObserver(() => {
      if (!touched.current || anchor.current) place();
    });
    ro.observe(el);
    for (const child of el.children) ro.observe(child);
    return () => ro.disconnect();
  }, [span, exhausted]);

  useEffect(() => {
    if (!oldestQ.isFetching) anchor.current = null;
  }, [oldestQ.isFetching]);

  const onScrollIntent = () => {
    touched.current = true;
    anchor.current = null;
  };

  const settle = () => {
    const el = scroller.current;
    wheelGoal.current = null;
    if (!el || !touched.current) return;
    if (el.scrollTop < 240 && !exhausted && !oldestQ.isFetching && !oldestQ.isError && anchor.current === null) {
      anchor.current = { month: shown[0] };
      setSpan((n) => n + 1);
    }
  };

  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    window.clearTimeout(settleTimer.current);
    settleTimer.current = window.setTimeout(settle, 140);
    const now = [...shown].reverse().find((m) => (sectionTop(m) ?? Infinity) <= el.scrollTop + 60) ?? shown[0];
    if (now !== visible) {
      setVisible(now);
      setSelected(null);
      setFocus(null);
    }
  };

  const wheelGoal = useRef<number | null>(null);
  const onWheelNative = (e: WheelEvent) => {
    const el = scroller.current;
    if (!el || !window.matchMedia("(min-width: 640px)").matches || !isMouseWheel(e)) return;
    e.preventDefault();
    onScrollIntent();
    const ts = shown.map((m) => sectionTop(m) ?? 0);
    const here = wheelGoal.current ?? ts.reduce((best, t, k) => (Math.abs(t - el.scrollTop) < Math.abs(ts[best] - el.scrollTop) ? k : best), 0);
    const next = Math.max(0, Math.min(ts.length - 1, here + Math.sign(e.deltaY)));
    if (next === here) return;
    wheelGoal.current = next;
    el.scrollTo({ top: ts[next], behavior: "smooth" });
  };
  const wheelRef = useRef(onWheelNative);
  wheelRef.current = onWheelNative;
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const listener = (e: WheelEvent) => wheelRef.current(e);
    el.addEventListener("wheel", listener, { passive: false });
    return () => el.removeEventListener("wheel", listener);
  }, []);

  const goToday = () => {
    onScrollIntent();
    setSelected(null);
    setFocus(null);
    const top = sectionTop(thisMonth);
    if (top !== null) scroller.current?.scrollTo({ top, behavior: "smooth" });
  };

  const setMode = (m: Mode) => {
    setModeState(m);
    setFocus(null);
    try {
      localStorage.setItem(MODE_KEY, m);
    } catch {
    }
  };

  const [vy, vm] = visible.split("-").map(Number);
  const visibleDays = useMemo(() => weekdaysOf(vy, vm - 1), [vy, vm]);
  const visibleDomestic = useMemo(() => byDate(visibleQ.data?.domestic), [visibleQ.data]);
  const visibleOverseas = useMemo(() => byDate(visibleQ.data?.overseas), [visibleQ.data]);

  const current =
    selected ??
    [...visibleDays].reverse().find((d) => d <= today && (visibleDomestic.has(d) || visibleOverseas.has(d))) ??
    null;
  const detailData = detailQ.data;

  const frequent = useMemo(() => {
    const count = new Map<string, { name: string; overseas: boolean; n: number }>();
    const tally = (items: LeaderDayItem[] | undefined, overseas: boolean) => {
      for (const day of items ?? [])
        for (const s of day.stocks) {
          const k = stockKey(s);
          const c = count.get(k) ?? { name: s.name, overseas, n: 0 };
          c.n++;
          count.set(k, c);
        }
    };
    const inMonth = (d: LeaderDayItem) => visibleDays.includes(d.date);
    if (mode !== "overseas") tally(visibleQ.data?.domestic.filter(inMonth), false);
    if (mode !== "domestic") tally(visibleQ.data?.overseas.filter(inMonth), true);
    return [...count.entries()].filter(([, c]) => c.n >= 2).sort((a, b) => b[1].n - a[1].n);
  }, [visibleQ.data, visibleDays, mode]);

  const caption = MODES.find((m) => m.key === mode)!.caption;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <h2 className="text-[20px] font-bold">주도주 캘린더</h2>
          <p className="mt-1 text-xs text-zinc-500">{caption}</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <div className="inline-flex rounded-[9px] bg-zinc-900 p-[3px]" role="group" aria-label="시장">
            {MODES.map((m) => (
              <button
                key={m.key}
                type="button"
                aria-pressed={mode === m.key}
                onClick={() => setMode(m.key)}
                className={`h-[26px] px-[11px] rounded-[7px] text-[13px] font-medium transition-colors ${
                  mode === m.key ? "bg-elevated text-zinc-100 shadow-sm" : "text-zinc-500 hover:text-zinc-300"
                }`}
              >
                {m.label}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-1">
            <span className="min-w-[6.5rem] text-center text-base font-bold num" aria-live="polite">
              {vy}년 {vm}월
            </span>
            <button
              type="button"
              onClick={goToday}
              className="ml-1 h-8 px-3 rounded-lg border border-zinc-800 text-sm font-semibold text-zinc-300 hover:bg-zinc-850"
            >
              오늘
            </button>
          </div>
        </div>
      </div>

      <div className="grid gap-[24px] xl:grid-cols-[minmax(0,1fr)_320px] items-start">
        <section className="rounded-[14px] border border-zinc-800 overflow-hidden text-[14px] leading-[normal]" aria-label="달력">
          <div
            ref={scroller}
            onScroll={onScroll}
            onWheel={onScrollIntent}
            onTouchMove={onScrollIntent}
            onPointerDown={onScrollIntent}
            onKeyDown={(event) => {
              if (["ArrowUp", "ArrowDown", "PageUp", "PageDown", "Home", "End", " "].includes(event.key)) onScrollIntent();
            }}
            tabIndex={0}
            aria-label="월별 주도주 달력"
            className="cal-scroll relative h-[calc(100dvh-11.5rem)] min-h-[28rem] overflow-y-auto overscroll-contain [overflow-anchor:none] sm:snap-y sm:snap-mandatory"
          >
            {shown.map((m) => (
              <MonthSection
                key={m}
                month={m}
                today={today}
                mode={mode}
                focus={focus}
                selected={current}
                onSelect={setSelected}
                sectionRef={(el) => {
                  if (el) sections.current.set(m, el);
                  else sections.current.delete(m);
                }}
              />
            ))}
          </div>
        </section>

        <aside className="grid gap-[16px] text-[14px] leading-[normal] sm:grid-cols-2 xl:grid-cols-1">
          <DayDetail
            date={current}
            today={today}
            mode={mode}
            domestic={current ? byDate(detailData?.domestic).get(current) : undefined}
            krClosed={current ? closedDates(detailData?.domestic).has(current) : false}
            usClosed={current ? closedDates(detailData?.overseas).has(current) : false}
            krLive={current ? liveDates(detailData?.domestic).has(current) : false}
            usLive={current ? liveDates(detailData?.overseas).has(current) : false}
            overseas={current ? byDate(detailData?.overseas).get(current) : undefined}
          />
          <section className="rounded-[14px] border border-zinc-800 p-[16px]">
            <h3 className="text-[15px] font-bold">자주 뽑힌 종목</h3>
            <p className="mt-[2px] mb-[12px] text-[12px] text-zinc-500">
              {frequent.length ? `${vm}월에 두 번 이상 뽑힌 종목` : "두 번 이상 뽑힌 종목이 없습니다"}
            </p>
            <div className="flex flex-col gap-[2px]">
              {frequent.map(([k, c]) => (
                <button
                  key={k}
                  type="button"
                  aria-pressed={focus === k}
                  onClick={() => setFocus(focus === k ? null : k)}
                  className={`-mx-[8px] grid grid-cols-[minmax(0,1fr)_36px] items-center gap-[10px] rounded-[8px] px-[8px] py-[6px] text-left hover:bg-zinc-850 ${
                    focus === k ? "bg-zinc-850 ring-1 ring-inset ring-emerald-700/50" : ""
                  }`}
                >
                  <div className="min-w-0">
                    <div className="mb-[5px] flex items-center gap-[6px] text-[13px] font-semibold">
                      {mode === "both" && <MarketTag overseas={c.overseas} />}
                      <span className="truncate">{c.name}</span>
                    </div>
                    <div className="h-[6px] rounded-[3px] bg-zinc-900 overflow-hidden">
                      <div
                        className={`h-full rounded-[3px] ${c.overseas ? "bg-(--leader-overseas)" : "bg-emerald-700/80"}`}
                        style={{ width: `${(c.n / frequent[0][1].n) * 100}%` }}
                      />
                    </div>
                  </div>
                  <span className="text-right text-[12px] text-zinc-300 num">{c.n}일</span>
                </button>
              ))}
            </div>
            {frequent.length > 0 && (
              <p className="mt-[10px] text-[11px] text-zinc-500">종목을 누르면 그 종목이 뽑힌 날만 달력에 남습니다.</p>
            )}
          </section>
        </aside>
      </div>
    </div>
  );
}

function MonthSection({
  month,
  today,
  mode,
  focus,
  selected,
  onSelect,
  sectionRef,
}: {
  month: string;
  today: string;
  mode: Mode;
  focus: string | null;
  selected: string | null;
  onSelect: (date: string) => void;
  sectionRef: (el: HTMLDivElement | null) => void;
}) {
  const [y, m] = month.split("-").map(Number);
  const { data, isLoading, isError } = useLeaderCalendar(month, month === today.slice(0, 7));
  const domestic = useMemo(() => byDate(data?.domestic), [data]);
  const overseas = useMemo(() => byDate(data?.overseas), [data]);
  const krClosed = useMemo(() => closedDates(data?.domestic), [data]);
  const usClosed = useMemo(() => closedDates(data?.overseas), [data]);
  const krLive = useMemo(() => liveDates(data?.domestic), [data]);
  const usLive = useMemo(() => liveDates(data?.overseas), [data]);
  const days = useMemo(() => weekdaysOf(y, m - 1), [y, m]);
  const leadingBlanks = days.length ? parse(days[0]).getDay() - 1 : 0;
  const trailingBlanks = (5 - ((leadingBlanks + days.length) % 5)) % 5;

  return (
    <div ref={sectionRef} className="flex min-h-full flex-col sm:min-h-0 sm:h-[max(100%,36rem)] sm:snap-start sm:snap-always">
      <div className="sticky top-0 z-[2] flex h-12 shrink-0 items-center border-b border-zinc-800 bg-zinc-950 px-[14px] text-[15px] font-bold num">
        {y}년 {m}월
      </div>
      <div className="hidden h-9 shrink-0 grid-cols-5 border-b border-zinc-800 sm:grid">
        {["월", "화", "수", "목", "금"].map((w) => (
          <div key={w} className="flex items-center px-[12px] text-[12px] font-semibold text-zinc-500">
            {w}
          </div>
        ))}
      </div>
      {isError ? (
        <p className="py-16 text-center text-[14px] text-zinc-500">달력을 불러오지 못했습니다</p>
      ) : (
        <div className="grid min-h-0 grid-cols-1 content-start sm:flex-1 gap-px bg-zinc-800 sm:grid-cols-5 sm:auto-rows-[minmax(0,1fr)]">
          {Array.from({ length: leadingBlanks }, (_, i) => (
            <div key={`b${i}`} className="hidden sm:block bg-zinc-900" />
          ))}
          {days.map((d) => (
            <DayCell
              key={d}
              date={d}
              today={today}
              mode={mode}
              loading={isLoading}
              domestic={domestic.get(d)}
              krClosed={krClosed.has(d)}
              usClosed={usClosed.has(d)}
              krLive={krLive.has(d)}
              usLive={usLive.has(d)}
              overseas={overseas.get(d)}
              selected={d === selected}
              focus={focus}
              onSelect={() => onSelect(d)}
            />
          ))}
          {Array.from({ length: trailingBlanks }, (_, i) => (
            <div key={`a${i}`} className="hidden sm:block bg-zinc-900" />
          ))}
        </div>
      )}
    </div>
  );
}

function isMouseWheel(e: WheelEvent): boolean {
  if (e.deltaMode === 1) return true;
  const legacy = (e as WheelEvent & { wheelDeltaY?: number }).wheelDeltaY;
  if (!legacy) return false;
  return legacy !== -3 * e.deltaY && legacy % 120 === 0;
}

function monthsBack(month: string, n: number): string[] {
  const [y, m] = month.split("-").map(Number);
  return Array.from({ length: n }, (_, i) => {
    const d = new Date(y, m - 1 - (n - 1 - i), 1);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
  });
}

function byDate(items: LeaderDayItem[] | undefined): Map<string, LeaderStockItem[]> {
  return new Map((items ?? []).filter((d) => !d.closed).map((d) => [d.date, d.stocks]));
}

function closedDates(items: LeaderDayItem[] | undefined): Set<string> {
  return new Set((items ?? []).filter((d) => d.closed).map((d) => d.date));
}

function liveDates(items: LeaderDayItem[] | undefined): Set<string> {
  return new Set((items ?? []).filter((d) => d.live).map((d) => d.date));
}

function weekdaysOf(y: number, m: number): string[] {
  const out: string[] = [];
  const last = new Date(y, m + 1, 0).getDate();
  for (let d = 1; d <= last; d++) {
    const date = new Date(y, m, d);
    if (date.getDay() !== 0 && date.getDay() !== 6) out.push(iso(date));
  }
  return out;
}

function MarketTag({ overseas }: { overseas: boolean }) {
  return (
    <span
      className={`shrink-0 rounded-[4px] px-[5px] py-px text-[10px] font-bold ${
        overseas ? "bg-(--leader-overseas-tint) text-(--leader-overseas)" : "bg-zinc-900 text-zinc-500"
      }`}
    >
      {overseas ? "해외" : "국내"}
    </span>
  );
}

function LiveBadge() {
  return (
    <span className="flex items-center gap-[4px] text-[10.5px] font-semibold text-emerald-600">
      <i className="h-[5px] w-[5px] animate-pulse rounded-full bg-current" />
      진행 중
    </span>
  );
}

function CountDots({ n }: { n: number }) {
  return (
    <span className="flex gap-[2px]" aria-label={`${n}종목`}>
      {Array.from({ length: MAX }, (_, i) => (
        <i key={i} className={`h-[4px] w-[4px] rounded-full bg-current ${i < n ? "opacity-75" : "opacity-20"}`} />
      ))}
    </span>
  );
}

function Rows({ stocks, limit, focus, more = true }: { stocks: LeaderStockItem[]; limit: number; focus: string | null; more?: boolean }) {
  return (
    <>
      {stocks.slice(0, limit).map((s, i) => (
        <div
          key={stockKey(s)}
          className={`flex items-baseline justify-between gap-[6px] rounded-[4px] ${
            i === 0 ? "text-[14px] font-bold text-zinc-100" : "text-[13px] text-zinc-300"
          } ${focus === stockKey(s) ? "-mx-[4px] px-[4px] bg-emerald-700/10 text-emerald-700" : ""}`}
        >
          <span className="truncate">{s.name}</span>
          <span className="shrink-0 text-[11px] font-semibold text-red-600 num">{pct(s.changeRate)}</span>
        </div>
      ))}
      {more && stocks.length > limit && <span className="text-[11px] text-zinc-500">외 {stocks.length - limit}종목</span>}
    </>
  );
}

function DayCell({
  date,
  today,
  mode,
  loading,
  domestic,
  krClosed,
  usClosed,
  krLive,
  usLive,
  overseas,
  selected,
  focus,
  onSelect,
}: {
  date: string;
  today: string;
  mode: Mode;
  loading: boolean;
  domestic: LeaderStockItem[] | undefined;
  krClosed: boolean;
  usClosed: boolean;
  krLive: boolean;
  usLive: boolean;
  overseas: LeaderStockItem[] | undefined;
  selected: boolean;
  focus: string | null;
  onSelect: () => void;
}) {
  const d = parse(date);
  const future = date > today;
  const showUs = mode !== "domestic" && (overseas !== undefined || usClosed);
  const showKr = mode !== "overseas" && domestic !== undefined;
  const closed = mode !== "overseas" && krClosed;
  const has = showUs || showKr || closed;
  const shown = [...(showKr ? domestic! : []), ...(showUs && overseas ? overseas : [])];
  const hit = focus !== null && shown.some((s) => stockKey(s) === focus);
  const dim = focus !== null && !hit;

  const tag =
    closed ? (
      <span className="text-[11px] font-semibold text-zinc-500">휴장</span>
    ) : mode === "domestic" && krLive ? (
      <LiveBadge />
    ) : date === today ? (
      <span className="text-[11px] font-semibold text-zinc-500">오늘</span>
    ) : mode === "domestic" && domestic ? (
      <span className="text-zinc-300">
        <CountDots n={domestic.length} />
      </span>
    ) : null;

  const body = loading ? (
    <Skeleton className="h-[64px] w-full" />
  ) : (
    <>
      {showKr && (
        <div className="flex flex-col gap-[3px]">
          {mode === "both" && (
            <div className="flex items-center justify-between text-[10.5px] font-bold text-zinc-500">
              <span className="flex items-center gap-[6px]">
                국내
                {krLive && <LiveBadge />}
              </span>
              {domestic!.length > 0 && <CountDots n={domestic!.length} />}
            </div>
          )}
          {domestic!.length ? (
            <Rows stocks={domestic!} limit={mode === "both" ? 2 : MAX} focus={focus} more={mode !== "both"} />
          ) : (
            <span className="text-[12px] text-zinc-500">주도주 없음</span>
          )}
        </div>
      )}
      {showUs && (
        <div className="-mx-[4px] flex flex-col gap-[3px] rounded-[8px] bg-(--leader-overseas-tint) px-[8px] py-[6px]">
          <div className="flex items-center justify-between text-[10.5px] font-bold text-(--leader-overseas)">
            <span className="flex items-center gap-[6px]">
              해외
              {usLive && <LiveBadge />}
            </span>
            {overseas && overseas.length > 0 && <CountDots n={overseas.length} />}
          </div>
          {usClosed ? (
            <span className="text-[12px] text-zinc-500">휴장</span>
          ) : overseas!.length ? (
            <Rows stocks={overseas!} limit={mode === "both" ? 2 : MAX} focus={focus} more={mode !== "both"} />
          ) : (
            <span className="text-[12px] text-zinc-500">주도주 없음</span>
          )}
        </div>
      )}
    </>
  );

  const head = (
    <div className="flex items-center justify-between gap-[6px]">
      <span className="flex items-baseline gap-[6px]">
        <span
          className={`num text-[13px] font-semibold ${
            date === today ? "-ml-[4px] rounded-full bg-zinc-100 px-[7px] text-zinc-950" : future ? "text-zinc-500" : "text-zinc-300"
          }`}
        >
          {d.getDate()}
        </span>
        <span className="text-[11px] text-zinc-500 sm:hidden">{WEEKDAYS[d.getDay()]}</span>
      </span>
      {tag}
    </div>
  );

  const base = `min-h-0 flex-col gap-[8px] px-[12px] pt-[10px] pb-[12px] text-left transition-[background-color,opacity] duration-150 ${
    dim ? "opacity-30" : ""
  } ${hit ? "bg-emerald-700/5" : "bg-zinc-950"} ${closed ? "leader-closed" : ""}`;

  if (!has && !loading) return <div className={`${base} hidden sm:flex`}>{head}</div>;
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={`${base} flex hover:bg-zinc-850 ${selected ? "relative z-[1] ring-2 ring-inset ring-zinc-100" : ""}`}
    >
      {head}
      <div className="flex min-h-0 flex-1 flex-col gap-[8px] sm:overflow-hidden sm:[mask-image:linear-gradient(to_bottom,black_calc(100%-14px),transparent)]">
        {body}
      </div>
    </button>
  );
}

function DayDetail({
  date,
  today,
  mode,
  domestic,
  krClosed,
  usClosed,
  krLive,
  usLive,
  overseas,
}: {
  date: string | null;
  today: string;
  mode: Mode;
  domestic: LeaderStockItem[] | undefined;
  krClosed: boolean;
  usClosed: boolean;
  krLive: boolean;
  usLive: boolean;
  overseas: LeaderStockItem[] | undefined;
}) {
  if (!date) {
    return (
      <section className="rounded-[14px] border border-zinc-800 p-[16px]">
        <h3 className="text-[15px] font-bold">기록이 없습니다</h3>
        <p className="mt-[2px] text-[12px] text-zinc-500">이 달에는 아직 남은 주도주가 없습니다.</p>
      </section>
    );
  }
  const d = parse(date);
  const krEmpty = krClosed
    ? "휴장"
    : date > today
      ? "아직 장이 열리기 전입니다"
      : domestic
        ? "거래대금 상위 중 오른 종목이 없었습니다"
        : "기록이 없습니다";
  const usEmpty = usClosed ? "휴장" : overseas ? "주도주 없음" : "기록이 없습니다";

  return (
    <section className="rounded-[14px] border border-zinc-800 p-[16px]">
      <h3 className="text-[15px] font-bold">
        {d.getMonth() + 1}월 {d.getDate()}일 ({WEEKDAYS[d.getDay()]})
      </h3>
      {mode !== "overseas" && <DetailList title="국내" stocks={domestic} empty={krEmpty} live={krLive} />}
      {mode !== "domestic" && <DetailList title="해외" stocks={overseas} empty={usEmpty} live={usLive} usd />}
    </section>
  );
}

function DetailList({
  title,
  stocks,
  empty,
  live,
  usd = false,
}: {
  title: string;
  stocks: LeaderStockItem[] | undefined;
  empty: string;
  live: boolean;
  usd?: boolean;
}) {
  return (
    <div className="mt-[14px]">
      <div className={`mb-[4px] flex justify-between text-[12px] font-bold ${usd ? "text-(--leader-overseas)" : "text-zinc-500"}`}>
        <span className="flex items-center gap-[6px]">
          {title}
          {live && <LiveBadge />}
        </span>
        {stocks && stocks.length > 0 && <span>{stocks.length}종목</span>}
      </div>
      {live && <p className="mb-[4px] text-[11px] text-zinc-500">마감 전이라 순위가 바뀔 수 있습니다.</p>}
      {stocks && stocks.length ? (
        <ol className="flex flex-col">
          {stocks.map((s) => (
            <li
              key={stockKey(s)}
              className="grid grid-cols-[18px_minmax(0,1fr)_auto] items-baseline gap-x-[10px] gap-y-[2px] border-t border-zinc-800 py-[8px] first:border-t-0"
            >
              <span className="text-[12px] font-semibold text-zinc-500 num">{s.rank}</span>
              <span className="truncate text-[14px] font-bold">{s.name}</span>
              <span className="text-right text-[12px] font-semibold text-red-600 num">{pct(s.changeRate)}</span>
              <span className="col-start-2 col-span-2 text-[11px] text-zinc-500 num">
                거래대금 {usd ? compactUsd(s.tradingValue) : formatKoreanMoney(s.tradingValue)}
              </span>
            </li>
          ))}
        </ol>
      ) : (
        <p className="py-[6px] text-[13px] text-zinc-500">{empty}</p>
      )}
    </div>
  );
}
