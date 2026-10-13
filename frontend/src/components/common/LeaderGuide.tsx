import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import type { StockMarket } from "../../lib/stockMarket";

/** 설명창 폭(px). 폰에서는 화면 폭에 맞춰 줄어든다 */
const WIDTH = 400;
/** 화면 가장자리와 띄우는 거리(px) */
const GUTTER = 16;
/** 마우스가 버튼에서 설명창으로 건너가는 동안(사이 8px) 닫히지 않게 기다리는 시간(ms) */
const LEAVE_DELAY = 150;

const strong = (text: string) => <b className="font-semibold text-zinc-100">{text}</b>;

/**
 * 시장마다 다른 규칙. 두 쪽 백엔드의 값과 맞춰야 한다 —
 * 국내 `leadingstock` (35위 컷·가중치 0.65·상장 첫날 보정), 해외 `overseasleadingstock` (40위 컷·0.55).
 */
const RULES: Record<StockMarket, ReactNode[]> = {
  domestic: [
    <>거래대금 상위 35종목 중 {strong("오른 종목")}만 봅니다.</>,
    <>거래대금의 가중치가 높습니다. {strong("실제로 돈이 들어온 종목")}이 주도주라고 봅니다.</>,
    <>상장 첫날 종목은 상승률이 부풀려져 있어 순위 보정이 있습니다. ETF와 스팩은 제외합니다.</>,
  ],
  overseas: [
    <>나스닥·뉴욕·아멕스 거래대금 상위 40종목 중 {strong("오른 종목")}만 봅니다.</>,
    <>거래대금의 가중치가 조금 높습니다. {strong("실제로 돈이 들어온 종목")}이 주도주라고 봅니다.</>,
    <>ETF는 제외합니다.</>,
  ],
};

/**
 * 주도주를 어떻게 고르는지 — 카드 제목 옆 `?`.
 *
 * 카드가 `overflow-hidden`이라 설명창은 body에 포털하고 버튼 아래에 고정 위치로 띄운다.
 * 스크롤하면 버튼과 어긋나므로 닫는다.
 *
 * 마우스를 올리면 열리고, 버튼과 설명창 밖으로 나가면 닫힌다. 터치 화면에는 hover가 없어
 * 누르면 열리고, 바깥을 누르거나 ×로 닫는다.
 */
export default function LeaderGuide({ market }: { market: StockMarket }) {
  const titleId = useId();
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState({ top: 0, left: 0, width: WIDTH });
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const leaveTimer = useRef<number | undefined>(undefined);

  const show = () => {
    window.clearTimeout(leaveTimer.current);
    setOpen(true);
  };
  const hideSoon = () => {
    window.clearTimeout(leaveTimer.current);
    leaveTimer.current = window.setTimeout(() => setOpen(false), LEAVE_DELAY);
  };
  useEffect(() => () => window.clearTimeout(leaveTimer.current), []);

  useLayoutEffect(() => {
    if (!open || !buttonRef.current) return;
    const r = buttonRef.current.getBoundingClientRect();
    const width = Math.min(WIDTH, window.innerWidth - GUTTER * 2);
    const left = Math.min(Math.max(GUTTER, r.left - 12), window.innerWidth - width - GUTTER);
    setPos({ top: r.bottom + 8, left, width });
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const close = () => setOpen(false);
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node;
      if (!panelRef.current?.contains(t) && !buttonRef.current?.contains(t)) close();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      close();
      buttonRef.current?.focus();
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    window.addEventListener("scroll", close, true);
    window.addEventListener("resize", close);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close);
    };
  }, [open]);

  return (
    <>
      {/* 카드 전체를 덮는 링크(z-[1]) 위로 올린다 */}
      <button
        ref={buttonRef}
        type="button"
        onMouseEnter={show}
        onMouseLeave={hideSoon}
        onClick={show}
        aria-expanded={open}
        aria-label="주도주를 고르는 방법"
        className={`relative z-[2] grid h-5 w-5 shrink-0 place-items-center rounded-full text-[12px] font-bold transition-colors ${
          open ? "bg-emerald-700 text-white" : "bg-zinc-800 text-zinc-300 hover:bg-zinc-700"
        }`}
      >
        ?
      </button>
      {open &&
        createPortal(
          <div
            ref={panelRef}
            role="dialog"
            aria-labelledby={titleId}
            style={{ top: pos.top, left: pos.left, width: pos.width }}
            onMouseEnter={show}
            onMouseLeave={hideSoon}
            className="fixed z-50 flex flex-col gap-3.5 rounded-2xl border border-zinc-800 bg-elevated p-[18px] shadow-xl"
          >
            <button
              type="button"
              onClick={() => setOpen(false)}
              aria-label="닫기"
              className="absolute right-3 top-3 p-1 text-lg leading-none text-zinc-400 hover:text-zinc-200"
            >
              ×
            </button>
            <h3 id={titleId} className="text-[15px] font-bold text-zinc-100">
              주도주는 이렇게 고릅니다
            </h3>
            <p className="-mt-1.5 text-[13.5px] text-zinc-300">
              오늘 <b className="font-semibold text-zinc-100">돈이 가장 많이 몰리면서 높은 등락률을 보여주는 종목</b>입니다.
            </p>
            <p className="-mb-1.5 text-xs font-semibold text-zinc-400">
              거래대금 순위와 상승률 순위가 둘 다 높아야 위로 올라옵니다
            </p>
            <div className="flex flex-col gap-1.5">
              <GuidePlot />
              <div className="flex items-center justify-end gap-3 text-[11.5px] text-zinc-400">
                <span className="flex items-center gap-1.5">
                  <i className="h-2.5 w-2.5 rounded-full bg-emerald-700" />
                  주도주
                </span>
                <span className="flex items-center gap-1.5">
                  <i className="h-2.5 w-2.5 rounded-full border-[1.5px] border-zinc-400" />
                  밀려난 종목
                </span>
              </div>
            </div>
            <ul className="flex list-disc flex-col gap-1.5 pl-[18px] text-[13px] text-zinc-300">
              {RULES[market].map((rule, i) => (
                <li key={i}>{rule}</li>
              ))}
            </ul>
          </div>,
          document.body,
        )}
    </>
  );
}

/**
 * 두 순위로 본 후보 자리 — **실제 데이터가 아닌 그림이다.**
 * 8종목 예시를 서비스와 같은 공식(백분위 가중 기하평균, 거래대금 0.65)으로 세웠을 때의 자리다.
 * 해외 가중치(0.55)로 세워도 위 5개는 같다 — 그래서 그림 하나를 두 시장이 함께 쓴다.
 * 값은 [상승률 순위, 거래대금 순위], 둘 다 1이 1위. 위 5개가 주도주다.
 *
 * 설명은 한 줄로 단다. 두 줄이면 이웃한 점·축 이름과 겹친다.
 */
const DOTS: { at: [number, number]; leader: boolean; note?: [string, string] }[] = [
  { at: [2, 2], leader: true, note: ["둘 다 2위", " → 주도주 1위"] },
  { at: [7, 1], leader: true, note: ["거래대금은 1위", ", 상승률은 낮음"] },
  { at: [3, 3], leader: true },
  { at: [5, 4], leader: true },
  { at: [4, 5], leader: true },
  { at: [8, 6], leader: false },
  { at: [6, 7], leader: false },
  { at: [1, 8], leader: false, note: ["상승률 1위", ", 거래대금 적음"] },
];
const N = DOTS.length;

function GuidePlot() {
  const L = 44, R = 300, T = 24, B = 196;
  // 순위 → 좌표. 1위가 오른쪽·위 끝
  const x = (rank: number) => R - ((rank - 1) / (N - 1)) * (R - L);
  const y = (rank: number) => T + ((rank - 1) / (N - 1)) * (B - T);
  const mid = (N + 1) / 2;

  return (
    <svg viewBox="0 0 320 236" className="h-auto w-full text-zinc-400" role="img" aria-label="거래대금 순위와 상승률 순위로 본 후보 종목 자리 예시">
      {/* 순위마다 옅은 격자. 설명창 바탕과 겹치지 않게 축과 같은 색을 흐리게 쓴다 */}
      <g className="stroke-zinc-500" strokeOpacity={0.2}>
        {Array.from({ length: N }, (_, i) => (
          <g key={i}>
            <line x1={x(i + 1)} y1={T - 12} x2={x(i + 1)} y2={B + 10} />
            <line x1={L - 8} y1={y(i + 1)} x2={R + 8} y2={y(i + 1)} />
          </g>
        ))}
      </g>
      <line x1={L - 8} y1={B + 10} x2={R + 8} y2={B + 10} className="stroke-zinc-500" />
      <line x1={L - 8} y1={T - 12} x2={L - 8} y2={B + 10} className="stroke-zinc-500" />
      <text x={R + 8} y={B + 28} textAnchor="end" fontSize={11} fill="currentColor">
        상승률 순위 높음 →
      </text>
      <text transform={`translate(${L - 20} ${B + 10}) rotate(-90)`} fontSize={11} fill="currentColor">
        거래대금 순위 높음 →
      </text>
      {DOTS.map(({ at: [rr, tr], leader }, i) => (
        <circle
          key={i}
          cx={x(rr)}
          cy={y(tr)}
          r={leader ? 5 : 4}
          strokeWidth={1.5}
          className={leader ? "fill-emerald-700 stroke-emerald-700" : "fill-elevated stroke-zinc-400"}
        />
      ))}
      {DOTS.filter((d) => d.note).map(({ at: [rr, tr], note }, i) => {
        // 왼쪽 절반의 점은 오른쪽에, 오른쪽 절반의 점은 왼쪽에 글을 단다
        const onLeft = rr > mid;
        return (
          <text key={i} x={x(rr) + (onLeft ? 10 : -10)} y={y(tr) + 4} textAnchor={onLeft ? "start" : "end"} fontSize={11}>
            <tspan fontWeight={600} className="fill-zinc-100">{note![0]}</tspan>
            <tspan className="fill-zinc-300">{note![1]}</tspan>
          </text>
        );
      })}
    </svg>
  );
}
