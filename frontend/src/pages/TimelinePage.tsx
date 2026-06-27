import { useEffect, useState } from "react";
import { useQueries } from "@tanstack/react-query";
import { apiFetch } from "../api/client";
import { QK } from "../api/queries";
import { todayStr } from "../components/common/DateNavigator";
import MiniMonthCalendar from "../components/timeline/MiniMonthCalendar";
import TimelineView, { type TimelineDay } from "../components/timeline/TimelineView";
import type { MarketSignalEventsResponse } from "../types";

const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m + 1)}-${pad(d)}`;

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
  const [month, setMonth] = useState(() => {
    const now = new Date();
    return new Date(now.getFullYear(), now.getMonth(), 1);
  });
  const [selectedDate, setSelectedDate] = useState<string | null>(today);

  const dates = tradingDays(month, today);

  const marketQs = useQueries({
    queries: dates.map((d) => ({
      queryKey: QK.marketSignalEvents(d),
      queryFn: () =>
        apiFetch<MarketSignalEventsResponse>(`/api/leading-stocks/market-signal-events?date=${d}`),
      staleTime: d === today ? 30_000 : Infinity, // 과거는 정적
    })),
  });

  const days: TimelineDay[] = dates.map((d, i) => ({
    date: d,
    markets: marketQs[i]?.data?.events ?? [],
  }));
  const isLoading = marketQs.some((q) => q.isLoading);

  // 선택일(또는 월 변경)에 맞춰 해당 날짜 섹션으로 스크롤.
  useEffect(() => {
    if (!selectedDate) return;
    const el = document.getElementById(`tl-day-${selectedDate}`);
    el?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [selectedDate, dates.length]);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-bold text-zinc-100">타임라인</h1>
        <p className="mt-1 text-sm text-zinc-500">
          그 달 거래일의 지수 이벤트를 3시간 단위로 이어 봅니다 · 달력에서 날짜를 누르면 그날로 이동
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[16rem_1fr] gap-4 items-start">
        <div className="lg:sticky lg:top-4">
          <MiniMonthCalendar
            month={month}
            selected={selectedDate}
            today={today}
            onShiftMonth={(delta) =>
              setMonth((m) => new Date(m.getFullYear(), m.getMonth() + delta, 1))
            }
            onSelect={setSelectedDate}
          />
        </div>
        <TimelineView days={days} selectedDate={selectedDate} isLoading={isLoading} />
      </div>
    </div>
  );
}
