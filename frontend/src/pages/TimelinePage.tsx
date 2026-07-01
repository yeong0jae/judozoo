import { useEffect, useRef, useState } from "react";
import { useQueries } from "@tanstack/react-query";
import { apiFetch } from "../api/client";
import { QK } from "../api/queries";
import { todayStr } from "../components/common/DateNavigator";
import MonthCalendar from "../components/timeline/MonthCalendar";
import TimelineView, { type TimelineDay } from "../components/timeline/TimelineView";
import type { MarketCloseSnapshotItem, OverseasIndexCloseSnapshotItem } from "../types";

const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m + 1)}-${pad(d)}`;

/** 오늘(또는 주말이면 직전 평일) = 가장 최근 거래일. 공휴일 보정은 못 하지만 데이터가 없어도 선택만 된다. */
function latestTradingDay(today: string): string {
  const [y, m, d] = today.split("-").map(Number);
  const dt = new Date(y, m - 1, d);
  while (dt.getDay() === 0 || dt.getDay() === 6) dt.setDate(dt.getDate() - 1);
  return ymd(dt.getFullYear(), dt.getMonth(), dt.getDate());
}

/** 보는 달의 거래일(평일) 중 오늘 이전까지, 오름차순. 미래는 데이터가 없어 제외. */
function tradingDays(month: Date, today: string): string[] {
  const y = month.getFullYear();
  const m = month.getMonth();
  const last = new Date(y, m + 1, 0).getDate();
  const out: string[] = [];
  for (let d = 1; d <= last; d++) {
    const wd = new Date(y, m, d).getDay();
    if (wd === 0 || wd === 6) continue;
    const ds = ymd(y, m, d);
    if (ds > today) continue;
    out.push(ds);
  }
  return out;
}

export default function TimelinePage() {
  const today = todayStr();
  const latest = latestTradingDay(today);
  const [month, setMonth] = useState(() => {
    const [y, m] = latest.split("-").map(Number);
    return new Date(y, m - 1, 1);
  });
  const [selectedDate, setSelectedDate] = useState<string | null>(latest);

  const dates = tradingDays(month, today);

  const marketQs = useQueries({
    queries: dates.map((d) => ({
      queryKey: QK.marketCloseSnapshots(d),
      queryFn: () =>
        apiFetch<MarketCloseSnapshotItem[]>(`/api/leading-stocks/market-close-snapshots?date=${d}`),
      staleTime: d === today ? 30_000 : Infinity, // 과거는 정적
    })),
  });

  const indexQs = useQueries({
    queries: dates.map((d) => ({
      queryKey: QK.overseasIndexCloseSnapshots(d),
      queryFn: () =>
        apiFetch<OverseasIndexCloseSnapshotItem[]>(
          `/api/overseas-leading-stocks/index-close-snapshots?date=${d}`,
        ),
      staleTime: d === today ? 30_000 : Infinity, // 과거는 정적
    })),
  });

  const days: TimelineDay[] = dates.map((d, i) => ({
    date: d,
    markets: marketQs[i]?.data ?? [],
    indices: indexQs[i]?.data ?? [],
  }));
  const isLoading = marketQs.some((q) => q.isLoading) || indexQs.some((q) => q.isLoading);
  const byDate = new Map(days.map((d) => [d.date, d]));

  const scrollRef = useRef<HTMLDivElement>(null);

  // 선택일에 맞춰 해당 날짜 섹션으로 스크롤. 컨테이너가 자체 스크롤되면 그것만 움직여
  // 페이지(window)는 건드리지 않는다(앱 헤더 가림 방지). 모바일(컨테이너 비스크롤)은 페이지 스크롤.
  useEffect(() => {
    if (!selectedDate) return;
    const el = document.getElementById(`tl-day-${selectedDate}`);
    const container = scrollRef.current;
    if (!el) return;
    if (container && container.scrollHeight > container.clientHeight) {
      const top =
        container.scrollTop + el.getBoundingClientRect().top - container.getBoundingClientRect().top;
      container.scrollTo({ top, behavior: "smooth" });
    } else {
      el.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
  }, [selectedDate, dates.length]);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-bold text-zinc-100">이슈</h1>
        <p className="mt-1 text-sm text-zinc-500">
          거래일별 코스피·코스닥·나스닥과 주요 이슈를 한곳에 모아 봅니다 · 달력에서 날짜를 누르면 그날로 이동
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_34rem] gap-4 items-start">
        <div>
          <MonthCalendar
            month={month}
            selected={selectedDate}
            today={today}
            byDate={byDate}
            onShiftMonth={(delta) =>
              setMonth((m) => new Date(m.getFullYear(), m.getMonth() + delta, 1))
            }
            onSelect={setSelectedDate}
          />
        </div>
        <div ref={scrollRef} className="lg:sticky lg:top-4 lg:max-h-[calc(100vh-9rem)] lg:overflow-y-auto pr-1">
          <TimelineView days={days} selectedDate={selectedDate} isLoading={isLoading} />
        </div>
      </div>
    </div>
  );
}
