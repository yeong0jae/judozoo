import { useMarketStatus } from "../../api/queries";

/** 오늘이 휴장(주말·공휴일)이면 안내 배너. 국내 장중 데이터 화면 상단에 둔다. */
export default function HolidayBanner() {
  const { data } = useMarketStatus();
  if (!data?.isHoliday) return null;
  return (
    <div className="rounded-xl bg-amber-50 border border-amber-200 px-4 py-2.5 text-sm font-medium text-amber-700 text-center">
      오늘은 휴장입니다
    </div>
  );
}
