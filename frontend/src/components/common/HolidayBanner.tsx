import { useMarketCalendarStatus } from "../../api/queries";
import type { MarketRegion } from "../../types";

export default function HolidayBanner({ region = "KR" }: { region?: MarketRegion }) {
  const { data } = useMarketCalendarStatus(region);
  if (!data?.isHoliday) return null;
  return (
    <div className="flex items-center gap-1.5 text-xs text-zinc-400">
      <span className="w-1.5 h-1.5 rounded-full bg-[#e0b357]" />
      <span className="font-semibold text-[#d9a441]">휴장</span>
      <span>오늘은 장이 열리지 않습니다</span>
    </div>
  );
}
