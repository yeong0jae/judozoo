import { useMemo } from "react";
import type { InvestorType, MarketSignalEventItem, MarketType } from "../../types";

const MARKET_LABEL: Record<MarketType, string> = { KOSPI: "코스피", KOSDAQ: "코스닥" };
const INVESTOR_LABEL: Record<InvestorType, string> = {
  FOREIGN: "외국인",
  INSTITUTION: "기관",
  INDIVIDUAL: "개인",
};

/** 3시간 단위 버킷 (장중 + 프리/애프터). hour ∈ [from, to). */
const BUCKETS = [
  { label: "장 전 · ~09:00", from: 0, to: 9 },
  { label: "09:00 ~ 12:00", from: 9, to: 12 },
  { label: "12:00 ~ 15:00", from: 12, to: 15 },
  { label: "15:00 ~ 18:00", from: 15, to: 18 },
  { label: "18:00 ~ · 애프터", from: 18, to: 24 },
];

export interface TimelineDay {
  date: string; // YYYY-MM-DD
  markets: MarketSignalEventItem[];
}

interface TLItem {
  time: string;
  hour: number;
  title: string;
  desc: string;
  rate: number | null;
}

const hhmm = (iso: string) => iso.slice(11, 16);
const hourOf = (iso: string) => Number(iso.slice(11, 13));
const WD = ["일", "월", "화", "수", "목", "금", "토"];

function eok(v: number): string {
  const a = Math.abs(v);
  if (a >= 10000) return `${(a / 10000).toFixed(1)}조`;
  return `${Math.round(a).toLocaleString()}억`;
}
function rateClass(rate: number | null): string {
  if (rate === null) return "text-zinc-500";
  return rate >= 0 ? "text-red-400" : "text-blue-400";
}
function rateText(rate: number | null): string {
  if (rate === null) return "";
  return `${rate >= 0 ? "+" : ""}${rate.toFixed(2)}%`;
}
function marketDesc(e: MarketSignalEventItem): string {
  const who = e.investor ? INVESTOR_LABEL[e.investor] : "";
  const amt = e.netAmountEok != null ? `${eok(e.netAmountEok)} ` : "";
  return `${who} ${amt}${e.side === "BUY" ? "순매수" : "순매도"}`.trim();
}

function toItems(markets: MarketSignalEventItem[]): TLItem[] {
  return markets
    .filter((e) => e.kind === "NET_BUY_LEVEL") // 투자자 순매수/순매도만 (연속 양봉·음봉 제외)
    .map((e) => ({
      time: hhmm(e.occurredAt),
      hour: hourOf(e.occurredAt),
      title: MARKET_LABEL[e.market],
      desc: marketDesc(e),
      rate: e.changeRate,
    }))
    .sort((x, y) => x.time.localeCompare(y.time));
}

/** 보는 달 거래일을 위→아래로 이어 붙인 지수 이벤트 타임라인. 미니 캘린더 선택 시 해당 섹션으로 스크롤. */
export default function TimelineView({
  days,
  selectedDate,
  isLoading,
}: {
  days: TimelineDay[];
  selectedDate: string | null;
  isLoading: boolean;
}) {
  if (isLoading && days.every((d) => d.markets.length === 0)) {
    return (
      <div className="rounded-2xl border border-white/[0.04] bg-zinc-900 p-8 text-sm text-zinc-500">
        불러오는 중…
      </div>
    );
  }
  return (
    <div className="space-y-6">
      {days.map((day) => (
        <DaySection key={day.date} day={day} selected={day.date === selectedDate} />
      ))}
    </div>
  );
}

function DaySection({ day, selected }: { day: TimelineDay; selected: boolean }) {
  const items = useMemo(() => toItems(day.markets), [day.markets]);
  const [y, m, d] = day.date.split("-").map(Number);
  const wd = WD[new Date(y, m - 1, d).getDay()];

  return (
    <section id={`tl-day-${day.date}`} className="scroll-mt-0">
      {/* 날짜 헤더 — 스크롤 중 상단 고정(sticky) */}
      <div className="sticky top-0 z-10 bg-zinc-950 flex items-baseline gap-2 py-2 border-b border-white/[0.08]">
        <h2 className={`text-base font-bold ${selected ? "text-blue-400" : "text-zinc-100"}`}>
          {m}월 {d}일 <span className="font-normal text-zinc-500 text-sm">({wd})</span>
        </h2>
        <span className="text-xs text-zinc-600">{items.length}건</span>
      </div>

      {items.length === 0 ? (
        <p className="text-sm text-zinc-600 py-4">기록된 이벤트가 없습니다</p>
      ) : (
        <div className="pt-1">
          {BUCKETS.map((b) => {
            const inBucket = items.filter((it) => it.hour >= b.from && it.hour < b.to);
            if (inBucket.length === 0) return null;
            return (
              <div key={b.label}>
                <div className="text-[11px] text-zinc-600 pt-3 pb-1 pl-1">{b.label}</div>
                {inBucket.map((it, i) => (
                  <Row key={i} item={it} />
                ))}
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}

function Row({ item }: { item: TLItem }) {
  return (
    <div className="flex items-center gap-3 py-3 px-2 -mx-2 rounded-lg border-b border-white/[0.04] hover:bg-white/[0.03]">
      <span className="num text-xs text-zinc-500 w-11 shrink-0">{item.time}</span>
      <span
        className={`w-1.5 h-1.5 rounded-full shrink-0 ${
          item.rate == null ? "bg-zinc-600" : item.rate >= 0 ? "bg-red-400" : "bg-blue-400"
        }`}
      />
      <div className="min-w-0 flex-1 flex items-baseline gap-2">
        <span className="text-sm text-zinc-200 shrink-0">{item.title}</span>
        <span className="text-sm text-zinc-400 truncate">{item.desc}</span>
      </div>
      {item.rate !== null && (
        <span className={`num text-sm shrink-0 ${rateClass(item.rate)}`}>{rateText(item.rate)}</span>
      )}
    </div>
  );
}
