import { Fragment } from "react";
import type { MarketFlow, MarketName } from "./model";
import { signed } from "./parts";

const tone = (n: number) => (n > 0 ? "text-red-400" : n < 0 ? "text-blue-400" : "text-zinc-400");

/** 열 — 현물(당일 · 마감 · 애프터 · 5일) | 선물(당일 · 5일). 값 배열 순서는 mock.MarketFlow 참고 */
const COLS: { label: string; time?: string; pick: (v: number[]) => number; split?: boolean }[] = [
  { label: "당일", pick: (v) => v[0] },
  { label: "마감", time: "15:00~15:40", pick: (v) => v[4] },
  { label: "애프터", time: "15:40~20:00", pick: (v) => v[5] },
  { label: "5일", pick: (v) => v[1] },
  { label: "당일", pick: (v) => v[2], split: true },
  { label: "5일", pick: (v) => v[3] },
];

export default function MarketFlowCard({
  /** 화면에는 그리지 않고 읽어 주기에만 쓴다 */
  title,
  flows,
  afterLive,
  night,
}: {
  title: string;
  flows: Record<MarketName, MarketFlow>;
  /** 애프터가 진행 중이면 점을 깜빡인다 */
  afterLive: boolean;
  night: { price: number; rate: number; note: string };
}) {
  const markets: MarketName[] = ["코스피", "코스닥"];
  return (
    <section aria-label={title} className="flex min-w-0 flex-col gap-3.5 rounded-3xl bg-zinc-900 px-6 py-[22px]">
      <div className="grid flex-1 content-between text-sm" style={{ gridTemplateColumns: "3.6rem 2.6rem repeat(6, minmax(0, 1fr))" }}>
        <span />
        <span />
        <span className="col-span-4 border-b border-zinc-800 px-2 pb-1.5 text-center text-xs font-semibold text-zinc-300">
          현물 <span className="font-normal text-zinc-500">억원 · KRX+NXT</span>
        </span>
        <span className="col-span-2 border-b border-l border-zinc-800 px-2 pb-1.5 text-center text-xs font-semibold text-zinc-300">
          선물 <span className="font-normal text-zinc-500">계약</span>
        </span>
        <span />
        <span />
        {COLS.map((c, k) => (
          <span key={k} className={`px-2 py-1.5 text-right text-xs leading-tight text-zinc-400 ${c.split ? "border-l border-zinc-800" : ""}`}>
            {c.label}
            {afterLive && k === 2 && <span className="ml-1 inline-block h-[5px] w-[5px] animate-pulse rounded-full bg-[#3182f6] align-middle" aria-label="진행 중" />}
            <span className="block text-[11px] text-zinc-500">{c.time ?? " "}</span>
          </span>
        ))}

        {markets.map((m, mi) => {
          const top = mi === 0 ? "" : "border-t border-zinc-800";
          const rows: [string, number[]][] = [
            ["외인", flows[m].foreign],
            ["기관", flows[m].institution],
          ];
          return (
            <Fragment key={m}>
              <span className={`row-span-2 flex items-center py-3 text-sm font-bold ${top}`}>{m}</span>
              {rows.map(([label, v], ri) => (
                <Fragment key={label}>
                  <span className={`flex items-center py-3 text-[13px] font-semibold text-zinc-300 ${ri === 0 ? top : ""}`}>{label}</span>
                  {COLS.map((c, k) => {
                    const n = c.pick(v);
                    return (
                      <span key={k} className={`num whitespace-nowrap px-2 py-3 text-right font-semibold ${tone(n)} ${ri === 0 ? top : ""} ${c.split ? "border-l border-zinc-800" : ""}`}>
                        {signed(n)}
                      </span>
                    );
                  })}
                </Fragment>
              ))}
            </Fragment>
          );
        })}
      </div>

      <div className="mt-auto flex items-center justify-between gap-2.5 border-t border-zinc-800 pt-3 text-[13px]">
        <span className="font-bold">코스피 야간선물</span>
        <span className="flex items-baseline gap-2">
          <span className="num text-[15px] font-bold">{night.price.toFixed(2)}</span>
          <span className={`num text-[13px] font-bold ${tone(night.rate)}`}>
            {night.rate > 0 ? "+" : ""}
            {night.rate.toFixed(2)}% · {night.note}
          </span>
        </span>
      </div>
    </section>
  );
}
