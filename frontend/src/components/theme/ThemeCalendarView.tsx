import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
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

/** 테마 대표 등락 = 거래대금 1위 종목의 등락률. 색바 색을 정한다. */
function themeRate(t: ThemeItem): number | null {
  return t.stocks[0]?.priceChangeRate ?? null;
}
function barColor(rate: number | null): string {
  if (rate === null) return "bg-zinc-600";
  return rate >= 0 ? "bg-red-400" : "bg-blue-400";
}

/**
 * 테마 캘린더 프레젠테이션 — 데이터(month/days)는 props로 받아 순수 렌더.
 * 토스 경제 캘린더 스타일: 좌 선택일 상세 + 우 큰 월 그리드 + 하단 섹터 트리맵(풀폭).
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
  const detailDate = selectedDate ?? today;

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

      {/* 좌: 선택일 상세(좁게) / 우: 큰 월 그리드(넓게) */}
      <div className="grid grid-cols-1 lg:grid-cols-[6fr_14fr] gap-4 items-start">
        <AnimatePresence mode="wait">
          <motion.div
            key={detailDate}
            initial={{ opacity: 0, x: -8 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: 8 }}
            transition={{ duration: 0.16, ease: [0.22, 1, 0.36, 1] }}
          >
            <DayDetail date={detailDate} themes={byDate.get(detailDate)?.themes ?? []} />
          </motion.div>
        </AnimatePresence>

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
      </div>

      {/* 하단 풀폭: 선택일(없으면 오늘) 섹터별 트리맵 */}
      <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl p-4 space-y-3">
        <div className="flex items-center justify-between flex-wrap gap-2">
          <h3 className="text-sm font-semibold text-zinc-200">
            {detailDate} 섹터별 현황
            <span className="text-xs text-zinc-500 font-normal"> · 거래대금 크기 / 등락률 색</span>
          </h3>
          <TreemapLegend />
        </div>
        <ThemeTreemap themes={byDate.get(detailDate)?.themes ?? []} />
      </section>
    </div>
  );
}

function DayDetail({ date, themes }: { date: string; themes: ThemeItem[] }) {
  return (
    <div className="bg-zinc-900 border border-white/[0.04] rounded-2xl p-4 lg:sticky lg:top-4">
      <h3 className="text-sm font-semibold text-zinc-200 mb-3">{date} 테마별 주도 종목</h3>
      {themes.length === 0 ? (
        <p className="text-sm text-zinc-500">이 날짜에 적재된 테마가 없습니다</p>
      ) : (
        <div className="space-y-3 max-h-[36rem] overflow-auto pr-1">
          {themes.map((t) => (
            <div key={t.rank} className="border-b border-white/[0.06] pb-3 last:border-0 last:pb-0">
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
    </motion.div>
  );
}
