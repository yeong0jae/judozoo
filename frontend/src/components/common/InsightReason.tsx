import type { ReactNode } from "react";
import type { InsightArticle, InsightReasonItem } from "../../types";

/**
 * 왜 오르나(026) — 목록의 한 줄과 상세의 카드.
 *
 * 키워드·사유 문장·기준 시각은 방문자에게도 보인다. 근거·관련 기사는 로그인일 때만 서버가 싣고,
 * 방문자에게는 건수만 와서 "n건은 로그인 후 볼 수 있습니다"를 그린다.
 */

function Keyword({ word }: { word: string }) {
  return (
    <span className="shrink-0 rounded-[5px] bg-blue-50 px-1.5 text-[11px] font-semibold leading-[18px] text-blue-700">
      {word}
    </span>
  );
}

/**
 * 목록 한 줄 — `[키워드] [키워드] 사유 문장`. 넘치면 문장을 말줄임한다.
 * **설명 없음이면 아무것도 그리지 않는다** — 목록이 "없음" 문구로 차지 않게(설명 없음 문구는 상세 카드에만).
 */
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

/** "2026-09-28T10:41:15" → "10:41". 해외도 한국 시각이다(홈 "오늘 1위" 줄과 같은 기준). */
function clock(iso: string): string {
  return iso.slice(11, 16);
}

/**
 * 상세 카드 — 로그인이면 기존 종목 상세의 머리 아래에(`head` 없이), 방문자면 로그인 안내 위에(`head`로 종목 머리를 얹어) 둔다.
 * 사유가 아직 없는 종목(주도주가 아니었거나 만들기 전)이면 그리지 않는다.
 */
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
          <span className="flex items-center gap-1.5">
            왜 오르나요?
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

/** 방문자용 카드 머리 — 종목 상세가 로그인 뒤라, 카드가 어느 종목 이야기인지 여기서 밝힌다. */
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
