import { useMarketCalendarStatus } from "../../api/queries";
import { formatRange, useMarketSessions, type MarketSession } from "../../lib/marketSession";

const TONE = {
  open: "bg-emerald-400",
  pre: "bg-warning",
  post: "bg-indigo-500",
} as const;

export default function SessionStrip({ size = "sm" }: { size?: "sm" | "lg" }) {
  const { kr, us } = useMarketSessions();
  const krHoliday = useMarketCalendarStatus("KR").data?.isHoliday;
  const usHoliday = useMarketCalendarStatus("US").data?.isHoliday;

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
