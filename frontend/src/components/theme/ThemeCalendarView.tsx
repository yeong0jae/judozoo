import { useMemo, useState } from "react";
import { motion } from "motion/react";
import type { ThemeDayItem, ThemeItem } from "../../types";
import Skeleton from "../common/Skeleton";
import ThemeTreemap, { TreemapLegend } from "./ThemeTreemap";
import { formatKoreanMoney } from "../../lib/format";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
const CELL_THEMES = 4; // 칸당 노출 테마 수

/** 로컬 기준 YYYY-MM-DD (toISOString의 UTC 변환 회피). */
function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(
    d.getDate(),
  ).padStart(2, "0")}`;
}

/** 테마 대표 등락 = 거래대금 1위 종목의 등락률. 색바·팝오버 등락 표시에 쓴다. */
function themeRate(t: ThemeItem): number | null {
  return t.stocks[0]?.priceChangeRate ?? null;
}
function barColor(rate: number | null): string {
  if (rate === null) return "bg-zinc-600";
  return rate >= 0 ? "bg-red-400" : "bg-blue-400";
}
function rateText(rate: number | null): string {
  if (rate === null) return "";
  return `${rate >= 0 ? "+" : ""}${rate.toFixed(2)}%`;
}
function rateClass(rate: number | null): string {
  if (rate === null) return "text-zinc-600";
  return rate >= 0 ? "text-red-400" : "text-blue-400";
}

/**
 * 테마 캘린더 프레젠테이션 — 데이터(month/days)는 props로 받아 순수 렌더.
 * 큰 월 그리드(풀폭) + 하단 섹터 트리맵(선택일 기준). 칸 hover 시 그날 전체 테마+등락 팝오버.
 */
export default function ThemeCalendarView({
  month,
  days,
  today,
  isLoading,
  onShiftMonth,
  onCapture,
  capturePending,
}: {
  month: Date;
  days: ThemeDayItem[];
  today: string;
  isLoading: boolean;
  onShiftMonth: (delta: number) => void;
  onCapture: () => void;
  capturePending: boolean;
}) {
  const [selectedDate, setSelectedDate] = useState<string | null>(null);

  const byDate = useMemo(() => {
    const map = new Map<string, ThemeDayItem>();
    days.forEach((d) => map.set(d.date, d));
    return map;
  }, [days]);

  const monthEnd = new Date(month.getFullYear(), month.getMonth() + 1, 0);
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

  const monthLabel = `${month.getFullYear()}년 ${month.getMonth() + 1}월`;
  const treemapDate = selectedDate ?? today;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div>
          <h2 className="text-lg font-semibold">테마 캘린더</h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            매 거래일 마감 시점 당일 거래대금 상위 테마 · 순환 흐름 파악용
          </p>
        </div>
        <motion.button
          onClick={onCapture}
          disabled={capturePending}
          whileTap={{ scale: 0.95 }}
          className="px-3 py-1.5 rounded-lg text-xs bg-zinc-800 hover:bg-zinc-700 border border-white/[0.06] disabled:opacity-50"
          title="오늘치 테마를 즉시 적재"
        >
          {capturePending ? "캡처 중..." : "오늘 캡처"}
        </motion.button>
      </div>

      <div className="space-y-3">
        <div className="flex items-center gap-3">
          <span className="text-base font-semibold tabular-nums">{monthLabel}</span>
          <div className="flex gap-1">
            <motion.button
              onClick={() => onShiftMonth(-1)}
              whileTap={{ scale: 0.85 }}
              className="px-2 py-0.5 rounded text-zinc-400 hover:bg-zinc-800"
              aria-label="이전 달"
            >
              ‹
            </motion.button>
            <motion.button
              onClick={() => onShiftMonth(1)}
              whileTap={{ scale: 0.85 }}
              className="px-2 py-0.5 rounded text-zinc-400 hover:bg-zinc-800"
              aria-label="다음 달"
            >
              ›
            </motion.button>
          </div>
        </div>

        {isLoading ? (
          <Skeleton className="h-[32rem] w-full" />
        ) : (
          <div>
            <div className="grid grid-cols-7">
              {WEEKDAYS.map((w, i) => (
                <div
                  key={w}
                  className={`pb-2 text-xs font-medium border-b border-white/[0.08] ${
                    i === 0 ? "text-red-400" : i === 6 ? "text-blue-400" : "text-zinc-400"
                  }`}
                >
                  {w}
                </div>
              ))}
            </div>
            <div className="grid grid-cols-7 gap-1.5 pt-1.5">
              {cells.map((d, i) =>
                d === null ? (
                  <div key={`empty-${i}`} className="min-h-28" />
                ) : (
                  <DayCell
                    key={ymd(d)}
                    day={d.getDate()}
                    themes={byDate.get(ymd(d))?.themes ?? []}
                    isToday={ymd(d) === today}
                    isSelected={ymd(d) === selectedDate}
                    onSelect={() => setSelectedDate(ymd(d))}
                  />
                ),
              )}
            </div>
          </div>
        )}
      </div>

      {/* 하단 풀폭: 선택일(없으면 오늘) 섹터별 트리맵 */}
      <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl p-4 space-y-3">
        <div className="flex items-center justify-between flex-wrap gap-2">
          <h3 className="text-sm font-semibold text-zinc-200">
            {treemapDate} 섹터별 현황
            <span className="text-xs text-zinc-500 font-normal"> · 거래대금 √스케일 / 등락률 색</span>
          </h3>
          <TreemapLegend />
        </div>
        <ThemeTreemap themes={byDate.get(treemapDate)?.themes ?? []} />
      </section>
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
    <motion.div
      onClick={onSelect}
      whileTap={{ scale: 0.98 }}
      className={`group relative min-h-28 rounded-xl p-2 flex flex-col gap-1.5 cursor-pointer transition-colors ${
        isSelected
          ? "ring-1 ring-inset ring-emerald-600 bg-zinc-900"
          : isToday
            ? "ring-1 ring-inset ring-zinc-700 bg-zinc-900/60"
            : "hover:bg-zinc-900/60"
      }`}
    >
      <span className={`text-xs num ${isToday ? "text-blue-400 font-semibold" : "text-zinc-500"}`}>
        {day}
        {isToday && <span className="ml-1 text-[10px]">오늘</span>}
      </span>
      <div className="flex flex-col gap-1">
        {themes.slice(0, CELL_THEMES).map((t) => (
          <div key={t.rank} className="flex items-center gap-1.5 min-w-0">
            <span className={`w-0.5 h-3 rounded-full shrink-0 ${barColor(themeRate(t))}`} />
            <span className="text-[11px] text-zinc-300 truncate">{t.name}</span>
            <span className="num text-[11px] text-amber-400/90 shrink-0 ml-auto">
              {formatKoreanMoney(t.tradingValue)}
            </span>
          </div>
        ))}
        {extra > 0 && <span className="text-[10px] text-zinc-600 pl-2">+{extra}</span>}
      </div>

      {/* hover 시 그날 전체 테마 팝오버 — 거래대금 + 대표 등락률(1위 종목) */}
      {themes.length > 0 && (
        <div className="hidden group-hover:block absolute left-0 top-full z-50 mt-1 w-72 max-h-80 overflow-auto rounded-md border border-zinc-700 bg-zinc-950 p-2 shadow-lg">
          {themes.map((t) => {
            const rate = themeRate(t);
            return (
              <div key={t.rank} className="flex items-baseline justify-between gap-2 py-0.5 text-xs">
                <span className="text-zinc-300 truncate">
                  <span className="text-zinc-600 num mr-1">{t.rank}</span>
                  {t.name}
                </span>
                <span className="shrink-0 flex items-baseline gap-1.5">
                  <span className="num text-amber-400">{formatKoreanMoney(t.tradingValue)}</span>
                  {rate !== null && (
                    <span className={`num w-14 text-right ${rateClass(rate)}`}>{rateText(rate)}</span>
                  )}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </motion.div>
  );
}
