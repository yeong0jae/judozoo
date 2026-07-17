import { useMarketStatus } from "../../api/queries";

/** 오늘이 휴장(주말·공휴일)이면 안내 배너. 국내 장중 데이터 화면 상단에 둔다. */
export default function HolidayBanner() {
  const { data } = useMarketStatus();
  if (!data?.isHoliday) return null;
  return (
    <div className="flex items-center gap-2 px-0.5 pb-3 border-b border-white/[0.06] text-[13.5px] text-zinc-400">
      <span className="w-1.5 h-1.5 rounded-full bg-[#e0b357]" />
      <span className="font-semibold text-[#d9a441]">휴장</span>
      <span>· 오늘은 장이 열리지 않습니다</span>
    </div>
  );
}
