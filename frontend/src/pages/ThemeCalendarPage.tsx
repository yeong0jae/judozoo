import { useState } from "react";
import { useMe } from "../api/auth";
import LoginGate from "../components/common/LoginGate";
import { useThemeCalendar } from "../api/queries";
import ThemeCalendarView from "../components/theme/ThemeCalendarView";

/** 로컬 기준 YYYY-MM-DD (toISOString의 UTC 변환 회피). */
function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(
    d.getDate(),
  ).padStart(2, "0")}`;
}

function ThemeCalendarPageInner() {
  // 보고 있는 월의 1일
  const [month, setMonth] = useState(() => {
    const now = new Date();
    return new Date(now.getFullYear(), now.getMonth(), 1);
  });

  const monthEnd = new Date(month.getFullYear(), month.getMonth() + 1, 0);
  const { data, isLoading } = useThemeCalendar(ymd(month), ymd(monthEnd));

  return (
    <ThemeCalendarView
      month={month}
      days={data?.days ?? []}
      today={ymd(new Date())}
      isLoading={isLoading}
      onShiftMonth={(delta) =>
        setMonth((m) => new Date(m.getFullYear(), m.getMonth() + delta, 1))
      }
    />
  );
}


/** 로그인한 사용자만 본다. 미로그인이면 데이터를 부르지 않는다 —
 *  호출해봐야 401이고, 화면 폴링 주기마다 반복된다. */
export default function ThemeCalendarPage() {
  const { data: me, isLoading } = useMe();
  if (isLoading) return null;
  if (!me?.authenticated) {
    return <LoginGate title="테마 캘린더" description="거래일마다 거래대금 상위 테마를 캡처해 순환 흐름을 보여줍니다. 구글 계정으로 로그인하면 바로 볼 수 있습니다." />;
  }
  return <ThemeCalendarPageInner />;
}
