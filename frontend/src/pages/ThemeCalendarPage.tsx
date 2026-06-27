import { useState } from "react";
import { useThemeCalendar } from "../api/queries";
import { useCaptureThemes } from "../api/mutations";
import ThemeCalendarView from "../components/theme/ThemeCalendarView";

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

  const monthEnd = new Date(month.getFullYear(), month.getMonth() + 1, 0);
  const { data, isLoading } = useThemeCalendar(ymd(month), ymd(monthEnd));
  const capture = useCaptureThemes();

  return (
    <ThemeCalendarView
      month={month}
      days={data?.days ?? []}
      today={ymd(new Date())}
      isLoading={isLoading}
      onShiftMonth={(delta) =>
        setMonth((m) => new Date(m.getFullYear(), m.getMonth() + delta, 1))
      }
      onCapture={() => capture.mutate()}
      capturePending={capture.isPending}
    />
  );
}
