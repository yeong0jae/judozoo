import { useMemo, useState } from "react";
import { useAddIssue, useDeleteIssue, useEditIssue } from "../../api/mutations";
import type {
  DailyIssueItem,
  MarketCloseSnapshotItem,
  MarketType,
  OverseasIndexCloseSnapshotItem,
} from "../../types";

const MARKET_LABEL: Record<MarketType, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };
const WD = ["일", "월", "화", "수", "목", "금", "토"];

export interface TimelineDay {
  date: string; // YYYY-MM-DD
  markets: MarketCloseSnapshotItem[];
  indices: OverseasIndexCloseSnapshotItem[]; // 해외지수(나스닥종합 등) 마감
  issues: DailyIssueItem[]; // 사용자가 직접 남긴 이슈 메모
}

/** 지수값 콤마 + 소수 둘째자리. */
function fmtIndex(v: number): string {
  return v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
function eok(v: number): string {
  const a = Math.abs(v);
  if (a >= 10000) return `${(a / 10000).toFixed(1)}조`;
  return `${Math.round(a).toLocaleString()}억`;
}
function signed(v: number): string {
  const sign = v > 0 ? "+" : v < 0 ? "-" : "";
  return `${sign}${eok(v)}`;
}
// 한국 거래소 관행 — 순매수(양)/상승 빨강, 순매도(음)/하락 파랑
function netClass(v: number): string {
  return v > 0 ? "text-red-400" : v < 0 ? "text-blue-400" : "text-zinc-500";
}
function upDown(rate: number | null): string {
  if (rate === null) return "text-zinc-400";
  return rate >= 0 ? "text-red-400" : "text-blue-400";
}
function rateText(rate: number | null): string {
  if (rate === null) return "";
  return `${rate >= 0 ? "+" : ""}${rate.toFixed(2)}%`;
}
function ordered(markets: MarketCloseSnapshotItem[]): MarketCloseSnapshotItem[] {
  return [...markets].sort((a) => (a.market === "KOSPI" ? -1 : 1));
}
function parts(date: string) {
  const [y, m, d] = date.split("-").map(Number);
  return { m, d, wd: WD[new Date(y, m - 1, d).getDay()] };
}

/**
 * 거래일 타임라인 — 위→아래로 과거 → 오늘 → 예정(미래), 날짜 오름차순. 미니 캘린더 선택 시 해당 섹션으로 스크롤.
 * 과거·오늘은 마감 스냅샷 + 이슈, 미래는 마감 데이터가 없어 이슈만(빈 날은 슬림 행) 노출한다.
 */
export default function TimelineView({
  days,
  selectedDate,
  today,
  isLoading,
}: {
  days: TimelineDay[];
  selectedDate: string | null;
  today: string;
  isLoading: boolean;
}) {
  if (isLoading && days.every((d) => d.markets.length === 0 && d.indices.length === 0)) {
    return (
      <div className="rounded-2xl border border-white/[0.04] bg-zinc-900 p-8 text-sm text-zinc-500">
        불러오는 중…
      </div>
    );
  }
  return (
    <div className="space-y-6">
      {days.map((day) => {
        if (day.date > today) {
          // 미래는 빈 날을 줄줄이 늘어놓지 않는다 — 이슈가 있거나 달력에서 고른 날만 노출.
          if (day.issues.length === 0 && day.date !== selectedDate) return null;
          return <FutureDay key={day.date} day={day} selected={day.date === selectedDate} />;
        }
        return (
          <DaySection
            key={day.date}
            day={day}
            selected={day.date === selectedDate}
            today={day.date === today}
          />
        );
      })}
    </div>
  );
}

function DaySection({ day, selected, today }: { day: TimelineDay; selected: boolean; today: boolean }) {
  const rows = useMemo(() => ordered(day.markets), [day.markets]);
  const { m, d, wd } = parts(day.date);

  return (
    <section id={`tl-day-${day.date}`} className="scroll-mt-0">
      <div className="sticky top-0 z-10 bg-zinc-950 flex items-center justify-between gap-2 py-2 border-b border-white/[0.08]">
        <div className="flex items-baseline gap-2">
          <h2 className={`text-base font-bold ${selected ? "text-blue-400" : "text-zinc-100"}`}>
            {m}월 {d}일 <span className="font-normal text-zinc-500 text-sm">({wd})</span>
          </h2>
          {today && <span className="text-xs font-medium text-blue-400">오늘</span>}
        </div>
        <div className="flex flex-wrap items-center justify-end gap-1">
          {rows.map((mk) => (
            <Chip key={mk.market} label={MARKET_LABEL[mk.market]} rate={mk.changeRate} />
          ))}
          {day.indices.map((ix) => (
            <Chip key={ix.code} label={ix.name} rate={ix.changeRate} />
          ))}
        </div>
      </div>

      <div className="pt-1">
        {rows.map((mk) => (
          <FlowRow key={mk.market} item={mk} />
        ))}
        {day.indices.map((ix) => (
          <IndexRow key={ix.code} item={ix} />
        ))}
      </div>

      <Issues date={day.date} issues={day.issues} />
    </section>
  );
}

/**
 * 미래 거래일 — 마감 데이터 없음. 이슈가 있거나 달력에서 선택한 날만 렌더되며(빈 미래는 목록에서 제외),
 * 예정 이슈 영역을 펼쳐 보여준다. 이슈가 아직 없으면 입력창에 바로 포커스한다.
 */
function FutureDay({ day, selected }: { day: TimelineDay; selected: boolean }) {
  const { m, d, wd } = parts(day.date);
  return (
    <section id={`tl-day-${day.date}`} className="scroll-mt-0">
      <div className="sticky top-0 z-10 bg-zinc-950 flex items-center gap-2 py-2 border-b border-white/[0.08]">
        <h2 className={`text-base font-bold ${selected ? "text-blue-400" : "text-zinc-300"}`}>
          {m}월 {d}일 <span className="font-normal text-zinc-500 text-sm">({wd})</span>
        </h2>
        <span className="text-[11px] font-medium text-amber-300/80">예정</span>
      </div>
      <Issues date={day.date} issues={day.issues} autoFocus={day.issues.length === 0} />
    </section>
  );
}

/** 이슈 영역 — 앱의 서브패널 관용구(rounded-xl bg-white/[0.02]). 그날의 이슈 목록 + 추가. */
function Issues({ date, issues, autoFocus }: { date: string; issues: DailyIssueItem[]; autoFocus?: boolean }) {
  return (
    <div className="mt-2 rounded-xl bg-white/[0.02] px-3 py-2.5">
      <div className="mb-1.5 flex items-center gap-1.5">
        <span className="w-1 h-3.5 rounded-full bg-amber-400/80" />
        <span className="text-xs font-semibold text-amber-300/90">이슈</span>
        {issues.length > 0 && <span className="num text-xs text-zinc-600">{issues.length}</span>}
      </div>
      <div>
        {issues.map((issue) => (
          <IssueRow key={issue.id} date={date} issue={issue} />
        ))}
      </div>
      <AddIssue date={date} empty={issues.length === 0} autoFocus={autoFocus} />
    </div>
  );
}

function Chip({ label, rate }: { label: string; rate: number | null }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-md bg-white/[0.04] px-1.5 py-0.5 text-[11px]">
      <span className="text-zinc-400">{label}</span>
      <span className={`num font-semibold ${upDown(rate)}`}>{rateText(rate)}</span>
    </span>
  );
}

function FlowRow({ item }: { item: MarketCloseSnapshotItem }) {
  return (
    <div className="flex items-center gap-3 py-2.5 px-2 -mx-2 rounded-lg border-b border-white/[0.04] hover:bg-white/[0.03]">
      <span
        className={`w-1.5 h-1.5 rounded-full shrink-0 ${
          item.changeRate == null ? "bg-zinc-600" : item.changeRate >= 0 ? "bg-red-400" : "bg-blue-400"
        }`}
      />
      <span className="text-sm text-zinc-200 shrink-0 w-12">{MARKET_LABEL[item.market]}</span>
      <div className="min-w-0 flex-1 flex items-baseline gap-2 text-sm">
        <Net label="개인" v={item.individualEok} />
        <span className="text-zinc-700">·</span>
        <Net label="외인" v={item.foreignEok} />
        <span className="text-zinc-700">·</span>
        <Net label="기관" v={item.institutionEok} />
      </div>
      {item.changeRate !== null && (
        <span className={`num text-sm shrink-0 ${upDown(item.changeRate)}`}>{rateText(item.changeRate)}</span>
      )}
    </div>
  );
}

function IndexRow({ item }: { item: OverseasIndexCloseSnapshotItem }) {
  return (
    <div className="flex items-center gap-3 py-2.5 px-2 -mx-2 rounded-lg border-b border-white/[0.04] hover:bg-white/[0.03]">
      <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${item.changeRate >= 0 ? "bg-red-400" : "bg-blue-400"}`} />
      <span className="text-sm text-zinc-200 shrink-0 w-12">{item.name}</span>
      <div className="min-w-0 flex-1 num text-sm text-zinc-400">{fmtIndex(item.indexValue)}</div>
      <span className={`num text-sm shrink-0 ${upDown(item.changeRate)}`}>{rateText(item.changeRate)}</span>
    </div>
  );
}

function Net({ label, v }: { label: string; v: number }) {
  return (
    <span className="shrink-0">
      <span className="text-zinc-500">{label} </span>
      <span className={`num ${netClass(v)}`}>{signed(v)}</span>
    </span>
  );
}

function IssueRow({ date, issue }: { date: string; issue: DailyIssueItem }) {
  const edit = useEditIssue(date);
  const del = useDeleteIssue(date);
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(issue.content);

  const cancel = () => {
    setText(issue.content);
    setEditing(false);
  };
  // 저장은 Enter로만 — blur/Esc는 취소로 둬 중복 PUT을 막는다.
  const save = () => {
    const content = text.trim();
    if (content && content !== issue.content) {
      edit.mutate({ id: issue.id, content }, { onSuccess: () => setEditing(false) });
    } else {
      cancel();
    }
  };

  if (editing) {
    return (
      <input
        autoFocus
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") save();
          if (e.key === "Escape") cancel();
        }}
        onBlur={cancel}
        className="w-full bg-white/[0.04] rounded-md px-2 py-1 my-0.5 text-sm text-zinc-100 outline-none ring-1 ring-amber-400/40"
      />
    );
  }

  return (
    <div className="group flex items-start gap-2 py-1 px-1 -mx-1 rounded-md hover:bg-white/[0.03]">
      <span className="mt-2 w-1 h-1 rounded-full shrink-0 bg-amber-400/60" />
      <button
        onClick={() => setEditing(true)}
        className="min-w-0 flex-1 text-left text-sm leading-6 text-zinc-200"
      >
        {issue.content}
      </button>
      <div className="flex shrink-0 items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
        <button
          onClick={() => setEditing(true)}
          className="p-1 rounded text-zinc-500 hover:text-zinc-200"
          aria-label="수정"
        >
          <PencilIcon className="w-3.5 h-3.5" />
        </button>
        <button
          onClick={() => del.mutate(issue.id)}
          disabled={del.isPending}
          className="p-1 rounded text-zinc-500 hover:text-red-400"
          aria-label="삭제"
        >
          <TrashIcon className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
}

function AddIssue({ date, empty, autoFocus }: { date: string; empty: boolean; autoFocus?: boolean }) {
  const add = useAddIssue(date);
  const [draft, setDraft] = useState("");
  const submit = () => {
    const content = draft.trim();
    if (!content || add.isPending) return;
    add.mutate(content, { onSuccess: () => setDraft("") });
  };
  return (
    <div className={`flex items-center gap-1 ${empty ? "" : "mt-1"}`}>
      <PlusIcon className="w-3.5 h-3.5 shrink-0 text-zinc-600" />
      <input
        autoFocus={autoFocus}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && submit()}
        placeholder={empty ? "이 날의 이슈를 기록해 보세요" : "이슈 추가"}
        className="min-w-0 flex-1 bg-transparent text-sm text-zinc-200 outline-none py-1"
      />
      <button
        onClick={submit}
        disabled={!draft.trim() || add.isPending}
        className="shrink-0 text-xs font-medium text-blue-400 hover:text-blue-300 disabled:text-zinc-700 disabled:cursor-not-allowed px-1"
      >
        추가
      </button>
    </div>
  );
}

function PlusIcon(props: React.SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" {...props}>
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}
function PencilIcon(props: React.SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...props}>
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z" />
    </svg>
  );
}
function TrashIcon(props: React.SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...props}>
      <path d="M3 6h18M8 6V4h8v2m-9 0v14h10V6" />
    </svg>
  );
}
