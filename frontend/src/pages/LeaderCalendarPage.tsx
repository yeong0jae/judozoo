import { useMemo, useState } from "react";
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
  { key: "both", label: "함께", caption: "직전 해외장과 당일 국내장 마감 기준 주도주" },
  { key: "domestic", label: "국내", caption: "당일 국내장 마감 기준 주도주" },
  { key: "overseas", label: "해외", caption: "직전 해외장 마감 기준 주도주" },
];

const pad = (n: number) => String(n).padStart(2, "0");
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = (s: string) => {
  const [y, m, d] = s.split("-").map(Number);
  return new Date(y, m - 1, d);
};
const short = (s: string) => {
  const d = parse(s);
  return `${d.getMonth() + 1}/${d.getDate()}(${WEEKDAYS[d.getDay()]})`;
};

/** 국내 날짜 칸에 붙는 해외장 — 직전 평일. 월요일이면 금요일 장. 백엔드 `previous_weekday`와 같은 규칙. */
function previousWeekday(s: string): string {
  const d = parse(s);
  do d.setDate(d.getDate() - 1);
  while (d.getDay() === 0 || d.getDay() === 6);
  return iso(d);
}

const pct = (rate: number) => `${rate > 0 ? "+" : ""}${rate.toFixed(1)}%`;

/** 달러 거래대금 → "$1.23B" / "$456.7M". 홈 해외 카드와 같은 표기. */
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
    /* 저장소를 못 쓰면 기본값 */
  }
  return "both";
}

/**
 * 주도주 캘린더 — 홈 주도주 카드가 마감 때 고른 종목을 날짜별로 본다.
 *
 * 한 칸 = 직전 해외장(위) + 당일 국내장(아래). 짝짓기는 여기서 한다 — 서버는 각 시장의
 * 현지 거래일로만 준다. 날 기록이 있는데 종목이 없으면 "주도주 없음", 날 기록이 없으면 비워 둔다.
 */
export default function LeaderCalendarPage() {
  const today = todayStr();
  const [view, setView] = useState(() => {
    const d = parse(today);
    return { y: d.getFullYear(), m: d.getMonth() };
  });
  const [mode, setModeState] = useState<Mode>(readMode);
  const [selected, setSelected] = useState<string | null>(null);
  const [focus, setFocus] = useState<string | null>(null);

  const month = `${view.y}-${pad(view.m + 1)}`;
  const { data, isLoading, isError } = useLeaderCalendar(month);

  const domestic = useMemo(() => byDate(data?.domestic), [data]);
  const overseas = useMemo(() => byDate(data?.overseas), [data]);
  const days = useMemo(() => weekdaysOf(view.y, view.m), [view]);

  const setMode = (m: Mode) => {
    setModeState(m);
    setFocus(null);
    try {
      localStorage.setItem(MODE_KEY, m);
    } catch {
      /* 기억 못 해도 동작한다 */
    }
  };
  const shift = (n: number) => {
    setView(({ y, m }) => {
      const d = new Date(y, m + n, 1);
      return { y: d.getFullYear(), m: d.getMonth() };
    });
    setSelected(null);
    setFocus(null);
  };
  const goToday = () => {
    const d = parse(today);
    setView({ y: d.getFullYear(), m: d.getMonth() });
    setSelected(null);
    setFocus(null);
  };

  // 고른 날이 없으면 이달의 마지막 기록일(없으면 오늘)을 연다
  const current =
    selected ??
    [...days].reverse().find((d) => d <= today && (domestic.has(d) || overseas.has(previousWeekday(d)))) ??
    null;

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
    const inMonth = (d: LeaderDayItem) => days.includes(d.date);
    const pairedUs = new Set(days.map(previousWeekday));
    if (mode !== "overseas") tally(data?.domestic.filter(inMonth), false);
    if (mode !== "domestic") tally(data?.overseas.filter((d) => pairedUs.has(d.date)), true);
    return [...count.entries()].filter(([, c]) => c.n >= 2).sort((a, b) => b[1].n - a[1].n);
  }, [data, days, mode]);

  const caption = MODES.find((m) => m.key === mode)!.caption;
  const leadingBlanks = days.length ? parse(days[0]).getDay() - 1 : 0;
  const trailingBlanks = (5 - ((leadingBlanks + days.length) % 5)) % 5;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <h2 className="text-xl font-bold">주도주 캘린더</h2>
          <p className="mt-1 text-xs text-zinc-500">{caption}</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <div className="inline-flex rounded-lg bg-zinc-900 p-0.5" role="group" aria-label="시장">
            {MODES.map((m) => (
              <button
                key={m.key}
                type="button"
                aria-pressed={mode === m.key}
                onClick={() => setMode(m.key)}
                className={`h-7 px-3 rounded-md text-sm font-semibold transition-colors ${
                  mode === m.key ? "bg-elevated text-zinc-100 shadow-sm" : "text-zinc-500 hover:text-zinc-300"
                }`}
              >
                {m.label}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-1">
            <NavButton label="이전 달" onClick={() => shift(-1)} d="M15 6l-6 6 6 6" />
            <span className="min-w-[6.5rem] text-center text-base font-bold num">
              {view.y}년 {view.m + 1}월
            </span>
            <NavButton label="다음 달" onClick={() => shift(1)} d="M9 6l6 6-6 6" />
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

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_20rem] items-start">
        <section className="rounded-2xl border border-zinc-800 overflow-hidden" aria-label="달력">
          <div className="hidden sm:grid grid-cols-5 border-b border-zinc-800">
            {["월", "화", "수", "목", "금"].map((w) => (
              <div key={w} className="px-3 py-2 text-xs font-semibold text-zinc-500">
                {w}
              </div>
            ))}
          </div>
          {isError ? (
            <p className="py-16 text-center text-sm text-zinc-500">달력을 불러오지 못했습니다</p>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-5 gap-px bg-zinc-800">
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
                  overseasDate={previousWeekday(d)}
                  overseas={overseas.get(previousWeekday(d))}
                  selected={d === current}
                  focus={focus}
                  onSelect={() => setSelected(d)}
                />
              ))}
              {Array.from({ length: trailingBlanks }, (_, i) => (
                <div key={`a${i}`} className="hidden sm:block bg-zinc-900" />
              ))}
            </div>
          )}
        </section>

        <aside className="grid gap-4 sm:grid-cols-2 xl:grid-cols-1">
          <DayDetail
            date={current}
            today={today}
            mode={mode}
            domestic={current ? domestic.get(current) : undefined}
            overseasDate={current ? previousWeekday(current) : null}
            overseas={current ? overseas.get(previousWeekday(current)) : undefined}
          />
          <section className="rounded-2xl border border-zinc-800 p-4">
            <h3 className="text-[15px] font-bold">자주 뽑힌 종목</h3>
            <p className="mt-0.5 mb-3 text-xs text-zinc-500">
              {frequent.length ? "이달 두 번 이상 뽑힌 종목" : "두 번 이상 뽑힌 종목이 없습니다"}
            </p>
            <div className="flex flex-col gap-0.5">
              {frequent.map(([k, c]) => (
                <button
                  key={k}
                  type="button"
                  aria-pressed={focus === k}
                  onClick={() => setFocus(focus === k ? null : k)}
                  className={`-mx-2 grid grid-cols-[minmax(0,1fr)_2.5rem] items-center gap-3 rounded-lg px-2 py-1.5 text-left hover:bg-zinc-850 ${
                    focus === k ? "bg-zinc-850 ring-1 ring-inset ring-emerald-700/50" : ""
                  }`}
                >
                  <div className="min-w-0">
                    <div className="mb-1 flex items-center gap-1.5 text-[13px] font-semibold">
                      {mode === "both" && <MarketTag overseas={c.overseas} />}
                      <span className="truncate">{c.name}</span>
                    </div>
                    <div className="h-1.5 rounded-full bg-zinc-900 overflow-hidden">
                      <div
                        className="h-full rounded-full bg-emerald-700/80"
                        style={{ width: `${(c.n / frequent[0][1].n) * 100}%` }}
                      />
                    </div>
                  </div>
                  <span className="text-right text-xs text-zinc-300 num">{c.n}일</span>
                </button>
              ))}
            </div>
            {frequent.length > 0 && (
              <p className="mt-2.5 text-[11px] text-zinc-500">종목을 누르면 그 종목이 뽑힌 날만 달력에 남습니다.</p>
            )}
          </section>
        </aside>
      </div>
    </div>
  );
}

function byDate(items: LeaderDayItem[] | undefined): Map<string, LeaderStockItem[]> {
  return new Map((items ?? []).map((d) => [d.date, d.stocks]));
}

/** 그달의 평일 — 주말 칸은 두지 않는다. */
function weekdaysOf(y: number, m: number): string[] {
  const out: string[] = [];
  const last = new Date(y, m + 1, 0).getDate();
  for (let d = 1; d <= last; d++) {
    const date = new Date(y, m, d);
    if (date.getDay() !== 0 && date.getDay() !== 6) out.push(iso(date));
  }
  return out;
}

function NavButton({ label, onClick, d }: { label: string; onClick: () => void; d: string }) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="grid h-8 w-8 place-items-center rounded-lg text-zinc-300 hover:bg-zinc-850"
    >
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d={d} />
      </svg>
    </button>
  );
}

function MarketTag({ overseas }: { overseas: boolean }) {
  return (
    <span className="shrink-0 rounded px-1.5 py-px text-[10px] font-bold bg-zinc-900 text-zinc-500">
      {overseas ? "해외" : "국내"}
    </span>
  );
}

/** 몇 종목이 뽑혔는지 — 5칸 점. */
function CountDots({ n }: { n: number }) {
  return (
    <span className="flex gap-0.5" aria-label={`${n}종목`}>
      {Array.from({ length: MAX }, (_, i) => (
        <i key={i} className={`h-1 w-1 rounded-full bg-current ${i < n ? "opacity-75" : "opacity-20"}`} />
      ))}
    </span>
  );
}

function Rows({ stocks, limit, focus }: { stocks: LeaderStockItem[]; limit: number; focus: string | null }) {
  return (
    <>
      {stocks.slice(0, limit).map((s, i) => (
        <div
          key={stockKey(s)}
          className={`flex items-baseline justify-between gap-1.5 rounded ${
            i === 0 ? "text-sm font-bold text-zinc-100" : "text-[13px] text-zinc-300"
          } ${focus === stockKey(s) ? "-mx-1 px-1 bg-emerald-700/10 text-emerald-700" : ""}`}
        >
          <span className="truncate">{s.name}</span>
          <span className="shrink-0 text-[11px] font-semibold text-red-600 num">{pct(s.changeRate)}</span>
        </div>
      ))}
      {stocks.length > limit && <span className="text-[11px] text-zinc-500">외 {stocks.length - limit}종목</span>}
    </>
  );
}

function DayCell({
  date,
  today,
  mode,
  loading,
  domestic,
  overseasDate,
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
  overseasDate: string;
  overseas: LeaderStockItem[] | undefined;
  selected: boolean;
  focus: string | null;
  onSelect: () => void;
}) {
  const d = parse(date);
  const future = date > today;
  const showUs = mode !== "domestic" && overseas !== undefined;
  const showKr = mode !== "overseas" && domestic !== undefined;
  const has = showUs || showKr;
  const shown = [...(showKr ? domestic! : []), ...(showUs ? overseas! : [])];
  const hit = focus !== null && shown.some((s) => stockKey(s) === focus);
  const dim = focus !== null && !hit;

  const tag =
    date === today ? (
      <span className="text-[11px] font-semibold text-zinc-500">오늘</span>
    ) : future && showUs ? (
      <span className="text-[11px] font-semibold text-zinc-500">국내 장 전</span>
    ) : mode === "domestic" && domestic ? (
      <span className="text-zinc-300">
        <CountDots n={domestic.length} />
      </span>
    ) : null;

  const body = loading ? (
    <Skeleton className="h-16 w-full" />
  ) : (
    <>
      {showUs && (
        <div className="-mx-1 flex flex-col gap-0.5 rounded-lg bg-zinc-900 px-2 py-1.5">
          <div className="flex items-center justify-between text-[10.5px] font-bold text-zinc-500">
            <span>해외 · {short(overseasDate)}</span>
            {overseas!.length > 0 && <CountDots n={overseas!.length} />}
          </div>
          {overseas!.length ? (
            <Rows stocks={overseas!} limit={mode === "both" ? 2 : MAX} focus={focus} />
          ) : (
            <span className="text-xs text-zinc-500">주도주 없음</span>
          )}
        </div>
      )}
      {showKr && (
        <div className="flex flex-col gap-0.5">
          {mode === "both" && (
            <div className="flex items-center justify-between text-[10.5px] font-bold text-zinc-500">
              <span>국내</span>
              {domestic!.length > 0 && <CountDots n={domestic!.length} />}
            </div>
          )}
          {domestic!.length ? (
            <Rows stocks={domestic!} limit={mode === "both" ? 3 : MAX} focus={focus} />
          ) : (
            <span className="text-xs text-zinc-500">주도주 없음</span>
          )}
        </div>
      )}
    </>
  );

  const head = (
    <div className="flex items-center justify-between gap-1.5">
      <span className="flex items-baseline gap-1.5">
        <span
          className={`num text-[13px] font-semibold ${
            date === today ? "-ml-1 rounded-full bg-zinc-100 px-1.5 text-zinc-950" : future ? "text-zinc-500" : "text-zinc-300"
          }`}
        >
          {d.getDate()}
        </span>
        <span className="text-[11px] text-zinc-500 sm:hidden">{WEEKDAYS[d.getDay()]}</span>
      </span>
      {tag}
    </div>
  );

  const base = `min-h-0 sm:min-h-[8.5rem] flex-col gap-2 p-2.5 sm:p-3 text-left transition-[background-color,opacity] duration-150 ${
    dim ? "opacity-30" : ""
  } ${hit ? "bg-emerald-700/5" : "bg-zinc-950"}`;

  // 폰에서는 기록 없는 날을 목록에서 뺀다
  if (!has && !loading) return <div className={`${base} hidden sm:flex`}>{head}</div>;
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={`${base} flex hover:bg-zinc-850 ${selected ? "relative z-[1] ring-2 ring-inset ring-zinc-100" : ""}`}
    >
      {head}
      {body}
    </button>
  );
}

function DayDetail({
  date,
  today,
  mode,
  domestic,
  overseasDate,
  overseas,
}: {
  date: string | null;
  today: string;
  mode: Mode;
  domestic: LeaderStockItem[] | undefined;
  overseasDate: string | null;
  overseas: LeaderStockItem[] | undefined;
}) {
  if (!date) {
    return (
      <section className="rounded-2xl border border-zinc-800 p-4">
        <h3 className="text-[15px] font-bold">기록이 없습니다</h3>
        <p className="mt-1 text-xs text-zinc-500">이 달에는 아직 남은 주도주가 없습니다.</p>
      </section>
    );
  }
  const d = parse(date);
  const krEmpty = date > today ? "아직 장이 열리기 전입니다" : domestic ? "거래대금 상위 중 오른 종목이 없었습니다" : "기록이 없습니다";
  const usEmpty = overseas ? "주도주 없음" : "기록이 없습니다";

  return (
    <section className="rounded-2xl border border-zinc-800 p-4">
      <h3 className="text-[15px] font-bold">
        {d.getMonth() + 1}월 {d.getDate()}일 ({WEEKDAYS[d.getDay()]})
      </h3>
      {mode !== "domestic" && overseasDate && (
        <DetailList title={`해외 · ${short(overseasDate)} 장`} stocks={overseas} empty={usEmpty} usd />
      )}
      {mode !== "overseas" && <DetailList title="국내" stocks={domestic} empty={krEmpty} />}
    </section>
  );
}

function DetailList({
  title,
  stocks,
  empty,
  usd = false,
}: {
  title: string;
  stocks: LeaderStockItem[] | undefined;
  empty: string;
  usd?: boolean;
}) {
  return (
    <div className="mt-3">
      <div className="mb-1 flex justify-between text-xs font-bold text-zinc-500">
        <span>{title}</span>
        {stocks && stocks.length > 0 && <span>{stocks.length}종목</span>}
      </div>
      {stocks && stocks.length ? (
        <ol className="flex flex-col">
          {stocks.map((s) => (
            <li
              key={stockKey(s)}
              className="grid grid-cols-[1.1rem_minmax(0,1fr)_auto] items-baseline gap-x-2.5 gap-y-0.5 border-t border-zinc-800 py-2 first:border-t-0"
            >
              <span className="text-xs font-semibold text-zinc-500 num">{s.rank}</span>
              <span className="truncate font-bold">{s.name}</span>
              <span className="text-right text-xs font-semibold text-red-600 num">{pct(s.changeRate)}</span>
              <span className="col-start-2 col-span-2 text-[11px] text-zinc-500 num">
                거래대금 {usd ? compactUsd(s.tradingValue) : formatKoreanMoney(s.tradingValue)}
              </span>
            </li>
          ))}
        </ol>
      ) : (
        <p className="py-1.5 text-[13px] text-zinc-500">{empty}</p>
      )}
    </div>
  );
}
