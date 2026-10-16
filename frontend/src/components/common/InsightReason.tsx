import type { ReactNode } from "react";
import type { InsightArticle, InsightReasonItem } from "../../types";

function Keyword({ word }: { word: string }) {
  return (
    <span className="shrink-0 rounded-[5px] bg-emerald-700/16 px-1.5 text-[11px] font-semibold leading-[18px] text-emerald-700">
      {word}
    </span>
  );
}

export function ReasonLine({ item, className = "" }: { item?: InsightReasonItem; className?: string }) {
  if (!item?.explained || !item.reason) return null;
  return (
    <span className={`flex min-w-0 items-center gap-1.5 text-xs leading-[18px] text-zinc-300 ${className}`}>
      {item.keywords.map((k) => (
        <Keyword key={k} word={k} />
      ))}
      <span className="truncate">{item.reason}</span>
    </span>
  );
}

function clock(iso: string): string {
  return iso.slice(11, 16);
}

export function ReasonCard({
  item,
  member,
  head,
}: {
  item?: InsightReasonItem;
  member: boolean;
  head?: ReactNode;
}) {
  if (!item) return null;
  const count = item.evidenceCount + item.relatedCount;
  return (
    <section className="flex flex-col gap-4 rounded-2xl border border-zinc-800 p-4 sm:p-5">
      {head}
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between gap-2 text-xs font-bold text-zinc-500">
          <span className="flex items-center gap-1.5 text-zinc-300">
            왜 올랐나요?
            <span className="rounded border border-zinc-800 px-1 text-[10.5px] font-semibold text-zinc-400">AI 요약</span>
          </span>
          <span className="num font-normal">{clock(item.generatedAt)} 기준</span>
        </div>
        {item.explained ? (
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1.5 text-[15px] font-semibold leading-relaxed text-zinc-100">
            {item.keywords.map((k) => (
              <Keyword key={k} word={k} />
            ))}
            <span>{item.reason}</span>
          </p>
        ) : (
          <p className="text-[15px] text-zinc-400">뚜렷한 상승 이유를 찾지 못했어요</p>
        )}
      </div>
      {count > 0 &&
        (member ? (
          <div className="flex flex-col gap-3.5">
            <Articles label="근거 기사" items={item.evidence ?? []} />
            <Articles label="관련 기사" items={item.related ?? []} />
          </div>
        ) : (
          <p className="rounded-xl bg-zinc-900 px-3 py-2.5 text-[13px] text-zinc-400">
            {item.evidenceCount > 0 ? "근거·관련 기사" : "관련 기사"} {count}건은 로그인 후 볼 수 있습니다
          </p>
        ))}
    </section>
  );
}

function Articles({ label, items }: { label: string; items: InsightArticle[] }) {
  if (items.length === 0) return null;
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-xs font-bold text-zinc-500">{label}</span>
      <ol className="flex flex-col gap-1.5">
        {items.map((a) => (
          <li
            key={a.url}
            className="grid grid-cols-[6.5rem_minmax(0,1fr)] items-baseline gap-2.5 rounded-xl bg-zinc-900 px-3 py-2 text-[13px] sm:grid-cols-[7.5rem_minmax(0,1fr)]"
          >
            <span className="truncate text-[11.5px] text-zinc-500">{a.source}</span>
            <a
              href={a.url}
              target="_blank"
              rel="noopener noreferrer"
              className="text-zinc-200 hover:underline focus-visible:underline"
            >
              {a.title}
              <span className="ml-1 text-[11px] text-zinc-500">↗</span>
            </a>
          </li>
        ))}
      </ol>
    </div>
  );
}

export function ReasonHead({ name, code, rate }: { name: string; code: string; rate: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <span className="min-w-0 truncate text-lg font-bold text-zinc-100">
        {name}
        <span className="num ml-1.5 text-xs font-normal text-zinc-500">{code}</span>
      </span>
      <span className="num shrink-0 text-base font-semibold">{rate}</span>
    </div>
  );
}
