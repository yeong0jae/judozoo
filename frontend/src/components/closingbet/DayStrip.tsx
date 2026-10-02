import type { Moment } from "./moment";

const SEGS: [Moment, string, string][] = [
  ["review", "복기", "09:05~15:00"],
  ["bet", "베팅", "15:00~20:00"],
  ["night", "대기", "20:00~08:00"],
  ["result", "결과", "08:00~09:05"],
];

const COPY: Record<Moment, { chip: string; title: string; timerLabel: string; color: string }> = {
  review: { chip: "복기 시간", title: "어제 판을 돌아볼 시간", timerLabel: "베팅 시작까지", color: "#4ade80" },
  bet: { chip: "베팅 중", title: "오늘 종가에 뭘 걸까요?", timerLabel: "마감까지", color: "#3182f6" },
  night: { chip: "체결 완료 · 대기", title: "베팅이 잠겼어요", timerLabel: "결과 공개까지", color: "#f59e0b" },
  result: { chip: "결과 발표 중", title: "어제 판 결과가 나오고 있어요", timerLabel: "최종 랭킹까지", color: "#f87171" },
  holiday: { chip: "휴장", title: "오늘은 쉬는 날이에요", timerLabel: "다음 판까지", color: "#8b95a1" },
};

/** 지금 — 하루 흐름 네 칸 중 지금 칸에 불이 들어오고, 다음 칸까지 남은 시간을 센다 */
export default function DayStrip({ moment, sub, timer }: { moment: Moment; sub: string; timer: string }) {
  const c = COPY[moment];
  return (
    <section aria-label="지금" className="grid grid-cols-1 items-center gap-x-7 gap-y-4 rounded-[20px] bg-zinc-900 px-5 py-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)_auto]">
      <div className="flex min-w-0 flex-col gap-1">
        <span className="flex flex-wrap items-center gap-2">
          <span className="rounded-full bg-zinc-850 px-2.5 py-0.5 text-xs font-bold" style={{ color: c.color }}>
            {c.chip}
          </span>
          <span className="text-xs text-zinc-500">모의 게임 · 실제 주문 없음</span>
        </span>
        <span className="text-[22px] font-bold tracking-tight">{c.title}</span>
        <span className="text-[13px] text-zinc-400">{sub}</span>
      </div>
      {/* 점과 선으로 이은 하루 흐름 — 티켓의 단계 표시와 같은 모양. 지금 칸만 파랗게 켜진다 */}
      <ol aria-label="하루 흐름" className="relative m-0 grid list-none grid-cols-4 p-0">
        <span aria-hidden className="absolute left-[12.5%] right-[12.5%] top-[4px] h-0.5 rounded bg-zinc-800" />
        {SEGS.map(([key, label, time]) => {
          const on = key === moment;
          return (
            <li key={key} aria-current={on ? "step" : undefined} className="relative flex flex-col items-center gap-1.5">
              <span
                className="block h-2.5 w-2.5 rounded-full"
                style={{ background: on ? "#3182f6" : "#3a3f47", boxShadow: on ? "0 0 0 3px rgba(49,130,246,0.25)" : undefined }}
              />
              <span className={`text-[15px] ${on ? "font-bold text-zinc-100" : "font-medium text-zinc-500"}`}>{label}</span>
              <span className="num whitespace-nowrap text-xs text-zinc-500">{time}</span>
            </li>
          );
        })}
      </ol>
      <div className="flex flex-col items-start gap-0.5 xl:items-end">
        <span className="text-xs text-zinc-400">{c.timerLabel}</span>
        <span className="num text-[28px] font-medium leading-none tracking-tight">{timer}</span>
      </div>
    </section>
  );
}
