import { useMemo, useState } from "react";
import { useThemeCalendar } from "../api/queries";
import { useCaptureThemes } from "../api/mutations";
import type { ThemeDayItem, ThemeItem } from "../types";
import Skeleton from "../components/common/Skeleton";
import { formatKoreanMoney } from "../lib/format";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
const CELL_THEMES = 3; // 칸당 노출 테마 수

/** 로컬 기준 YYYY-MM-DD (toISOString의 UTC 변환 회피). */
function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(
    d.getDate(),
  ).padStart(2, "0")}`;
}

export default function ThemeCalendarPage() {
  // 보고 있는 월의 1일
  const [month, setMonth] = useState(() => {
    const now = new Date();
    return new Date(now.getFullYear(), now.getMonth(), 1);
  });
  const [selectedDate, setSelectedDate] = useState<string | null>(null);

  const monthEnd = new Date(month.getFullYear(), month.getMonth() + 1, 0);
  const from = ymd(month);
  const to = ymd(monthEnd);

  const { data, isLoading } = useThemeCalendar(from, to);
  const capture = useCaptureThemes();

  // date(YYYY-MM-DD) → 테마 목록
  const byDate = useMemo(() => {
    const map = new Map<string, ThemeDayItem>();
    data?.days.forEach((d) => map.set(d.date, d));
    return map;
  }, [data]);

  // 달력 그리드: 1일 앞 빈칸 + 말일 뒤 빈칸으로 7열 정렬
  const cells = useMemo(() => {
    const lead = month.getDay(); // 0=일
    const total = lead + monthEnd.getDate();
    const rows = Math.ceil(total / 7) * 7;
    return Array.from({ length: rows }, (_, i) => {
      const dayNum = i - lead + 1;
      if (dayNum < 1 || dayNum > monthEnd.getDate()) return null;
      return new Date(month.getFullYear(), month.getMonth(), dayNum);
    });
  }, [month, monthEnd]);

  const todayStr = ymd(new Date());
  const monthLabel = `${month.getFullYear()}년 ${month.getMonth() + 1}월`;
  const shiftMonth = (delta: number) =>
    setMonth((m) => new Date(m.getFullYear(), m.getMonth() + delta, 1));

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div>
          <h2 className="text-lg font-semibold">테마 캘린더</h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            매 거래일 마감 시점 당일 거래대금 상위 테마 · 순환 흐름 파악용
          </p>
        </div>
        <button
          onClick={() => capture.mutate()}
          disabled={capture.isPending}
          className="px-3 py-1.5 rounded text-xs bg-zinc-800 hover:bg-zinc-700 border border-zinc-700 disabled:opacity-50"
          title="오늘치 테마를 즉시 적재"
        >
          {capture.isPending ? "캡처 중..." : "오늘 캡처"}
        </button>
      </div>

      {/* 왼쪽: 캘린더(6.5) / 오른쪽: 선택일 상세(3.5) */}
      <div className="grid grid-cols-1 lg:grid-cols-[13fr_7fr] gap-4 items-start">
        <div className="space-y-4">
          {/* 월 이동 */}
          <div className="flex items-center justify-center gap-4">
            <button
              onClick={() => shiftMonth(-1)}
              className="px-2 py-1 rounded hover:bg-zinc-800 text-zinc-400"
              aria-label="이전 달"
            >
              ‹
            </button>
            <span className="text-sm font-medium tabular-nums w-28 text-center">
              {monthLabel}
            </span>
            <button
              onClick={() => shiftMonth(1)}
              className="px-2 py-1 rounded hover:bg-zinc-800 text-zinc-400"
              aria-label="다음 달"
            >
              ›
            </button>
          </div>

          {isLoading ? (
            <Skeleton className="h-96 w-full" />
          ) : (
            <div className="grid grid-cols-7 gap-px bg-zinc-800 border border-zinc-800 rounded-lg">
              {WEEKDAYS.map((w, i) => (
                <div
                  key={w}
                  className={`bg-zinc-950 py-2 text-center text-xs font-medium ${
                    i === 0 ? "text-red-400" : i === 6 ? "text-blue-400" : "text-zinc-400"
                  }`}
                >
                  {w}
                </div>
              ))}
              {cells.map((d, i) =>
                d === null ? (
                  <div key={`empty-${i}`} className="bg-zinc-950 min-h-24" />
                ) : (
                  <DayCell
                    key={ymd(d)}
                    day={d.getDate()}
                    themes={byDate.get(ymd(d))?.themes ?? []}
                    isToday={ymd(d) === todayStr}
                    isSelected={ymd(d) === selectedDate}
                    onSelect={() => setSelectedDate(ymd(d))}
                  />
                ),
              )}
            </div>
          )}
        </div>

        {selectedDate ? (
          <DayDetail
            date={selectedDate}
            themes={byDate.get(selectedDate)?.themes ?? []}
          />
        ) : (
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 text-sm text-zinc-500">
            날짜를 선택하면 그날 테마별 주도 종목이 표시됩니다
          </div>
        )}
      </div>
    </div>
  );
}

function DayDetail({ date, themes }: { date: string; themes: ThemeItem[] }) {
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
      <h3 className="text-sm font-semibold text-zinc-200 mb-3">{date} 테마별 주도 종목</h3>
      {themes.length === 0 ? (
        <p className="text-sm text-zinc-500">이 날짜에 적재된 테마가 없습니다</p>
      ) : (
        <div className="space-y-3">
          {themes.map((t) => (
            <div key={t.rank} className="border-b border-zinc-800 pb-3 last:border-0 last:pb-0">
              <div className="flex items-baseline justify-between gap-2">
                <span className="text-sm font-medium text-zinc-200">
                  <span className="text-zinc-600 num mr-1.5">{t.rank}</span>
                  {t.name}
                </span>
                <span className="num text-sm text-amber-400 shrink-0">
                  {formatKoreanMoney(t.tradingValue)}
                </span>
              </div>
              <div className="flex flex-wrap gap-x-3 gap-y-1 mt-1.5">
                {t.stocks.map((s) => (
                  <span key={s.stockCode} className="text-xs text-zinc-400">
                    {s.stockName}{" "}
                    <span className="num text-zinc-500">{formatKoreanMoney(s.tradingValue)}</span>
                    {s.priceChangeRate !== null && (
                      <span
                        className={`num ml-1 ${
                          s.priceChangeRate >= 0 ? "text-red-400" : "text-blue-400"
                        }`}
                      >
                        {s.priceChangeRate >= 0 ? "+" : ""}
                        {s.priceChangeRate.toFixed(2)}%
                      </span>
                    )}
                  </span>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function DayCell({
  day,
  themes,
  isToday,
  isSelected,
  onSelect,
}: {
  day: number;
  themes: ThemeItem[];
  isToday: boolean;
  isSelected: boolean;
  onSelect: () => void;
}) {
  const extra = themes.length - CELL_THEMES;
  return (
    <div
      onClick={onSelect}
      className={`group relative bg-zinc-900 min-h-24 p-1.5 flex flex-col gap-1 cursor-pointer hover:bg-zinc-800/60 ${
        isSelected ? "ring-1 ring-inset ring-emerald-600" : ""
      }`}
    >
      <span
        className={`text-xs num ${
          isToday
            ? "inline-flex items-center justify-center w-5 h-5 rounded-full bg-emerald-700 text-white"
            : "text-zinc-500"
        }`}
      >
        {day}
      </span>
      <div className="flex flex-col gap-0.5">
        {themes.slice(0, CELL_THEMES).map((t) => (
          <span key={t.rank} className="text-xs truncate">
            <span className="text-zinc-300">{t.name}</span>{" "}
            <span className="num text-amber-400">{formatKoreanMoney(t.tradingValue)}</span>
          </span>
        ))}
        {extra > 0 && <span className="text-[10px] text-zinc-600">+{extra}</span>}
      </div>

      {/* hover 시 그날 전체 테마 팝오버 — "+N"에 가려진 나머지까지 */}
      {themes.length > 0 && (
        <div className="hidden group-hover:block absolute left-0 top-full z-50 mt-1 w-60 max-h-72 overflow-auto rounded-md border border-zinc-700 bg-zinc-950 p-2 shadow-lg">
          {themes.map((t) => (
            <div key={t.rank} className="flex items-baseline justify-between gap-2 py-0.5 text-xs">
              <span className="text-zinc-300 truncate">
                <span className="text-zinc-600 num mr-1">{t.rank}</span>
                {t.name}
              </span>
              <span className="num shrink-0 text-amber-400">
                {formatKoreanMoney(t.tradingValue)}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
