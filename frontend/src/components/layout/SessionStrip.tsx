import { useMarketCalendarStatus } from "../../api/queries";
import { formatRange, useMarketSessions, type MarketSession } from "../../lib/marketSession";

/** 구간별 점 색. 휴장 노랑은 이 줄이 대신하기 전 휴장 배너가 쓰던 값 그대로다. */
const TONE = {
  open: "bg-emerald-400",
  pre: "bg-warning",
  post: "bg-indigo-500",
} as const;

/**
 * 지금 어느 장이 도는지 — 화면 제목 아래 한 줄.
 *
 * 국내는 늘 자리를 지킨다(휴장·장 닫힘도 말한다). 해외는 프리마켓·정규장일
 * 때만 나타나고 그 밖에는 사라진다 — 앱이 미국 정규장 데이터만 받아서,
 * 나머지 시간에 해외를 적어 봐야 알려주는 게 없다.
 */
export default function SessionStrip({ size = "sm" }: { size?: "sm" | "lg" }) {
  const { kr, us } = useMarketSessions();
  const krHoliday = useMarketCalendarStatus("KR").data?.isHoliday;
  const usHoliday = useMarketCalendarStatus("US").data?.isHoliday;

  // 홈에서는 이 줄이 화면의 머리라 한 단계 크게, 헤더 부제 자리에서는 작게
  const lg = size === "lg";

  return (
    <div
      className={`flex flex-wrap items-center gap-y-1 ${
        lg ? "gap-x-5 text-sm" : "gap-x-4 text-xs mt-1"
      }`}
    >
      {krHoliday ? (
        <Chip dot="bg-[#e0b357]" label="국내 휴장" labelClass="text-[#d9a441] font-semibold" lg={lg} />
      ) : kr ? (
        <Session session={kr} lg={lg} />
      ) : (
        <Chip dot="bg-zinc-600" label="국내 장 닫힘" lg={lg} />
      )}

      {us && !usHoliday && <Session session={us} lg={lg} />}
    </div>
  );
}

function Session({ session, lg }: { session: MarketSession; lg: boolean }) {
  return (
    <Chip
      dot={TONE[session.tone]}
      label={session.name}
      labelClass={lg ? "text-zinc-200 font-medium" : "text-zinc-300"}
      time={formatRange(session)}
      lg={lg}
    />
  );
}

function Chip({
  dot,
  label,
  labelClass = "text-zinc-500",
  time,
  lg = false,
}: {
  dot: string;
  label: string;
  labelClass?: string;
  time?: string;
  lg?: boolean;
}) {
  return (
    <span className={`flex items-center whitespace-nowrap ${lg ? "gap-2" : "gap-1.5"}`}>
      <span
        className={`inline-block rounded-full ${dot} ${lg ? "w-2 h-2" : "w-1.5 h-1.5"}`}
      />
      <span className={labelClass}>{label}</span>
      {time && <span className={`num text-zinc-500 ${lg ? "text-[13px]" : ""}`}>{time}</span>}
    </span>
  );
}
