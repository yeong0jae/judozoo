import { useMarketCalendarStatus } from "../../api/queries";
import type { MarketRegion } from "../../types";

/** 오늘이 휴장(주말·공휴일)이면 안내 배너. [region] KR(국내)/US(해외), 기본 국내. */
export default function HolidayBanner({ region = "KR" }: { region?: MarketRegion }) {
  const { data } = useMarketCalendarStatus(region);
  if (!data?.isHoliday) return null;
  return (
    <div className="flex items-center gap-2 px-0.5 pb-3 border-b border-zinc-800 text-[13.5px] text-zinc-400">
      <span className="w-1.5 h-1.5 rounded-full bg-[#e0b357]" />
      <span className="font-semibold text-[#d9a441]">휴장</span>
      <span>· 오늘은 장이 열리지 않습니다</span>
    </div>
  );
}
