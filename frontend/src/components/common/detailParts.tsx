import { useState, type ReactNode } from "react";

export function ChartCard({ title, action, children }: { title: ReactNode; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4 rounded-2xl border border-zinc-800 p-4 sm:p-5">
      <div className="flex min-h-7 items-center justify-between gap-2 text-xs font-bold text-zinc-500">
        <span>{title}</span>
        {action}
      </div>
      {children}
    </section>
  );
}

export const CHART_H = "h-[21.25rem] 2xl:h-[26rem]";

export function ChartEmpty({ children }: { children: ReactNode }) {
  return <div className={`${CHART_H} flex items-center justify-center text-xs text-zinc-600`}>{children}</div>;
}

export function Chevron({ dir }: { dir: "left" | "right" }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d={dir === "left" ? "M15 6l-6 6 6 6" : "M9 6l6 6-6 6"} />
    </svg>
  );
}

export function Segmented<T extends string>({
  label,
  items,
  value,
  onChange,
}: {
  label: string;
  items: [T, string][];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <div role="tablist" aria-label={label} className="flex shrink-0 rounded-xl bg-zinc-800 p-0.5 text-xs">
      {items.map(([key, text]) => (
        <button
          key={key}
          type="button"
          role="tab"
          aria-selected={value === key}
          onClick={() => onChange(key)}
          className={`rounded-lg px-3 py-1.5 transition-colors ${
            value === key ? "bg-elevated font-medium text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
          }`}
        >
          {text}
        </button>
      ))}
    </div>
  );
}

export type FilterResult = {
  filterName: string;
  criteriaDescription: string;
  actualValue: string;
  passed: boolean;
  value?: number | null;
  threshold?: number | null;
};

const CONDITION_COPY: Record<string, { title: string; why: string }> = {
  거래대금순위: { title: "거래대금 순위", why: "주도주는 돈이 몰리는 곳에서 나와요." },
  시가총액: { title: "회사 규모", why: "작은 회사는 적은 돈에도 가격이 크게 움직여요." },
  당일등락률: { title: "오늘 상승률", why: "많이 거래돼도 덜 오른 종목은 시장을 이끈다고 보기 어려워요." },
  "당일 등락률": { title: "오늘 상승률", why: "많이 거래돼도 덜 오른 종목은 시장을 이끈다고 보기 어려워요." },
  "프로그램 양매수": { title: "프로그램 매매", why: "규칙대로 움직이는 큰 자금이 들어오는지 봐요." },
  "최근 고가 대비 현재가": { title: "최근 고점과의 거리", why: "고점 근처라야 위에서 본전에 팔려는 물량이 적어요." },
  "시가 대비 현재가": { title: "시가 위에 있나요", why: "시가 아래로 밀리면 손해 본 사람들의 매도가 나오기 쉬워요." },
  "전일 등락률": { title: "어제 상승률", why: "이틀 연속 크게 오르면 차익 매물이 쏟아지기 쉬워요." },
  시초가: { title: "시작 가격", why: "너무 높게 시작하면 장중에 밀리기 쉬워요." },
};

const pct = (v: number) => `${v > 0 ? "+" : ""}${Number.isInteger(v) ? v : v.toFixed(1)}%`;
const CONDITION_SCALES: Record<
  string,
  { min: number; max: number; pass: "below" | "above"; label: (v: number) => string; names?: Record<number, string> }
> = {
  거래대금순위: { min: 1, max: 60, pass: "below", label: (v) => `${v}위` },
  당일등락률: { min: 0, max: 30, pass: "above", label: pct },
  "당일 등락률": { min: 0, max: 30, pass: "above", label: pct },
  "최근 고가 대비 현재가": { min: -20, max: 0, pass: "above", label: pct, names: { 0: "고가" } },
  "시가 대비 현재가": { min: -10, max: 20, pass: "above", label: pct, names: { 0: "시가" } },
  "전일 등락률": { min: -10, max: 30, pass: "below", label: pct },
  시초가: { min: -10, max: 20, pass: "below", label: pct },
};

const CONDITION_GROUPS: { title: string; filters: string[] }[] = [
  { title: "돈이 몰리는 큰 종목인가요?", filters: ["거래대금순위", "시가총액"] },
  { title: "오늘 실제로 사고 있나요?", filters: ["당일등락률", "당일 등락률", "프로그램 양매수"] },
  { title: "지금 가격은 어디쯤에 있나요?", filters: ["최근 고가 대비 현재가", "시가 대비 현재가"] },
  { title: "이미 너무 오르진 않았나요?", filters: ["전일 등락률", "시초가"] },
];

export type ConditionKit = {
  title: string;
  subtitle: (count: number) => string;
  groups: { title: string; filters: string[] }[];
  copy: Record<string, { title: string; why: string }>;
  scales: typeof CONDITION_SCALES;
};

const LEADING_KIT: ConditionKit = {
  title: "주도주 체크리스트",
  subtitle: (n) => `오늘 시장을 이끄는 종목인지 ${n}가지로 봐요.`,
  groups: CONDITION_GROUPS,
  copy: CONDITION_COPY,
  scales: CONDITION_SCALES,
};

function groupConditions(results: FilterResult[], kit: ConditionKit): { title: string; items: FilterResult[] }[] {
  const groups = kit.groups.map((g) => ({
    title: g.title,
    items: results.filter((r) => g.filters.includes(r.filterName)),
  }));
  const known = new Set(kit.groups.flatMap((g) => g.filters));
  groups.push({ title: "그 밖의 조건", items: results.filter((r) => !known.has(r.filterName)) });
  return groups.filter((g) => g.items.length > 0);
}

function scoreOf(passed: number, total: number): { label: string; className: string } {
  if (passed === total) return { label: "모두 통과", className: "bg-emerald-400/15 text-emerald-400" };
  if (passed >= total * 0.75) return { label: "대부분 통과", className: "bg-emerald-400/15 text-emerald-400" };
  if (passed >= total * 0.5) return { label: "절반쯤 통과", className: "bg-zinc-800 text-zinc-300" };
  return { label: "대부분 미달", className: "bg-red-400/15 text-red-300" };
}

export function LeadingConditions({ results, kit = LEADING_KIT }: { results: FilterResult[]; kit?: ConditionKit }) {
  const groups = groupConditions(results, kit);
  const [{ step: rawStep, leaving }, setPage] = useState<{ step: number; leaving: number | null }>({ step: 0, leaving: null });
  const step = Math.min(rawStep, Math.max(groups.length - 1, 0));
  const go = (i: number) => setPage({ step: (i + groups.length) % groups.length, leaving: step });
  const passedCount = results.filter((r) => r.passed).length;
  const score = scoreOf(passedCount, results.length);

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h3 className="text-base font-bold text-zinc-100">{kit.title}</h3>
          <p className="break-keep text-[13px] leading-relaxed text-zinc-500">{kit.subtitle(results.length)}</p>
        </div>
        <span className={`inline-flex shrink-0 items-baseline rounded-2xl px-3.5 py-2 ${score.className}`}>
          <span className="num text-[22px] font-bold">{passedCount}</span>
          <span className="num text-[13px] opacity-75">/{results.length}</span>
          <span className="ml-1.5 text-xs font-semibold">{score.label}</span>
        </span>
      </div>

      {groups.length > 0 && (
        <div className="relative grid">
          {groups.length > 1 && (
            <button
              type="button"
              tabIndex={-1}
              aria-hidden
              onClick={() => go(step + 1)}
              className="absolute inset-0 z-0 cursor-pointer"
            />
          )}
          {groups.map((g, i) => (
            <div
              key={g.title}
              aria-hidden={i !== step}
              className={`pointer-events-none relative flex flex-col gap-2 transition-[opacity,transform,visibility] duration-[320ms] ease-[cubic-bezier(0.22,1,0.36,1)] [grid-area:1/1] motion-reduce:transition-none ${
                i === step ? "translate-y-0 opacity-100" : i === leaving ? "invisible -translate-y-1.5 opacity-0" : "invisible translate-y-3.5 opacity-0"
              }`}
            >
              <h4 className="px-0.5 text-[15px] font-bold text-zinc-100">{g.title}</h4>
              <ul className="flex flex-col gap-1.5">
                {g.items.map((r) => (
                  <ConditionItem key={r.filterName} result={r} kit={kit} />
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}

      {groups.length > 1 && (
        <nav aria-label="묶음 넘기기" className="flex items-center justify-between gap-3">
          <button
            type="button"
            onClick={() => go(step - 1)}
            aria-label="이전 묶음"
            className="inline-flex h-11 w-11 items-center justify-center rounded-xl bg-zinc-900 text-zinc-400 transition-colors hover:bg-zinc-800 hover:text-zinc-100"
          >
            <Chevron dir="left" />
          </button>
          <div className="flex items-center">
            {groups.map((g, i) => {
              const all = g.items.every((r) => r.passed);
              const on = i === step;
              return (
                <button
                  key={g.title}
                  type="button"
                  onClick={() => go(i)}
                  aria-label={`${i + 1}번째 묶음, ${all ? "모두 통과" : "미충족 있음"}`}
                  aria-current={on ? "step" : undefined}
                  className="inline-flex h-6 items-center px-1"
                >
                  <span
                    className={`block h-2 rounded-full transition-all duration-200 ${
                      on ? "w-5 bg-zinc-100" : all ? "w-2 bg-emerald-500" : "w-2 bg-zinc-600"
                    }`}
                  />
                </button>
              );
            })}
          </div>
          <button
            type="button"
            onClick={() => go(step + 1)}
            aria-label={step === groups.length - 1 ? "처음 묶음으로" : "다음 묶음"}
            className="inline-flex h-11 w-11 items-center justify-center rounded-xl bg-zinc-900 text-zinc-400 transition-colors hover:bg-zinc-800 hover:text-zinc-100"
          >
            <Chevron dir="right" />
          </button>
        </nav>
      )}
    </section>
  );
}

function ConditionItem({ result: r, kit }: { result: FilterResult; kit: ConditionKit }) {
  const copy = kit.copy[r.filterName];
  return (
    <li className="flex gap-3 rounded-2xl bg-zinc-900 px-4 py-3.5">
      <span
        className={`inline-flex h-[22px] w-[22px] shrink-0 items-center justify-center rounded-[7px] ${
          r.passed ? "bg-emerald-400/15 text-emerald-500" : "bg-red-400/15 text-red-400"
        }`}
      >
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d={r.passed ? "M5 12.5l4.5 4.5L19 7.5" : "M7 7l10 10M17 7L7 17"} />
        </svg>
        <span className="sr-only">{r.passed ? "충족" : "미충족"}</span>
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div className="flex items-baseline justify-between gap-3">
          <span className="break-keep text-sm font-bold text-zinc-100">{copy?.title ?? r.filterName}</span>
          <span className={`num shrink-0 whitespace-nowrap text-sm font-bold ${r.passed ? "text-emerald-400" : "text-red-400"}`}>
            {r.actualValue}
          </span>
        </div>
        <ConditionScale result={r} scales={kit.scales} />
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1 text-xs leading-normal">
          <span className="shrink-0 rounded-md bg-zinc-800 px-1.5 py-px text-zinc-400">기준 {r.criteriaDescription}</span>
          {copy && <span className="break-keep text-zinc-500">{copy.why}</span>}
        </div>
      </div>
    </li>
  );
}

function ConditionScale({ result: r, scales }: { result: FilterResult; scales: ConditionKit["scales"] }) {
  const scale = scales[r.filterName];
  if (!scale || r.value == null || r.threshold == null) return null;
  const { min, max } = scale;
  const at = (v: number) => (Math.max(0, Math.min(1, (v - min) / (max - min))) * 100);
  const [from, to] = scale.pass === "below" ? [min, r.threshold] : [r.threshold, max];
  const ticks = [min, r.threshold, max].filter((v, i, all) => all.indexOf(v) === i);
  return (
    <div className="flex flex-col gap-1 px-0.5" aria-hidden>
      <div className="relative h-2 rounded bg-zinc-800">
        <span className="absolute inset-y-0 rounded bg-emerald-500/30" style={{ left: `${at(from)}%`, width: `${at(to) - at(from)}%` }} />
        <span
          className="absolute -top-1 h-4 w-1 -translate-x-1/2 rounded-sm bg-zinc-100 ring-2 ring-zinc-900"
          style={{ left: `${at(r.value)}%` }}
        />
      </div>
      <div className="relative h-3.5">
        {ticks.map((v, i) => (
          <span
            key={v}
            className={`num absolute top-0 whitespace-nowrap text-[10.5px] ${v === r.threshold ? "text-zinc-400" : "text-zinc-600"} ${
              i === 0 ? "left-0" : i === ticks.length - 1 ? "right-0" : "-translate-x-1/2"
            }`}
            style={i > 0 && i < ticks.length - 1 ? { left: `${at(v)}%` } : undefined}
          >
            {scale.names?.[v] ?? scale.label(v)}
          </span>
        ))}
      </div>
    </div>
  );
}
