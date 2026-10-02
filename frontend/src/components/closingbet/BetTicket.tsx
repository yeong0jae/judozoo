import type { CSSProperties } from "react";
import { GradeAvatar, pct, won } from "./parts";
import { GRADE_PERF, GRADE_TEXT, GRADE_TINT, type BetStock, type Grade } from "./model";
import { goLogin } from "../../api/auth";
import { colorByPnL } from "../../lib/format";

/**
 * 베팅 → 체결 → 매도·랭킹. 지금 단계에 불이 들어온다.
 * 매도 시각은 종목 따라 — NXT 종목은 08:05, 아니면 09:05. 모르거나 고르기 전에는 "내일 아침".
 */
function Steps({ at, nxt }: { at: number; nxt?: boolean | null }) {
  const steps: [string, string][] = [
    ["베팅", "15:00~20:00"],
    ["체결", "20:00 종가"],
    ["매도 · 랭킹", nxt === true ? "내일 08:05" : nxt === false ? "내일 09:05" : "내일 아침"],
  ];
  return (
    <ol aria-label="종베 진행" className="relative m-0 grid list-none grid-cols-3 p-0 pb-0.5">
      <span aria-hidden className="absolute left-[16.667%] right-[16.667%] top-[5px] h-0.5 rounded bg-zinc-800" />
      <span aria-hidden className="absolute left-[16.667%] top-[5px] h-0.5 rounded bg-zinc-400" style={{ width: `${(Math.min(2, at) / 2) * 66.667}%` }} />
      {steps.map(([label, time], i) => {
        const now = i === at;
        const done = i < at;
        return (
          <li key={label} className="relative flex flex-col items-center gap-1">
            <span
              className="relative block h-3 w-3 rounded-full"
              style={{ background: now ? "#3182f6" : done ? "#8b95a1" : "#333d4b", boxShadow: now ? "0 0 0 5px rgba(49,130,246,0.25)" : undefined }}
            />
            <span className={`text-[13px] ${now ? "font-bold text-zinc-100" : done ? "font-semibold text-zinc-300" : "font-semibold text-zinc-500"}`}>{label}</span>
            <span className="num whitespace-nowrap text-[11px] text-zinc-500">{time}</span>
          </li>
        );
      })}
    </ol>
  );
}

/**
 * 모의 종베 티켓 — 머리 칸과 절취선이 등급색으로 물든다(금·은·동·철).
 * 금(S)·은(A)은 고르는 순간 빛이 한 번 지나간다.
 */
export default function BetTicket({
  stock,
  grade,
  amount,
  onAmount,
  confirmed,
  onConfirm,
  onEdit,
  onCancel,
  filled = false,
  nxt,
  dayLabel,
  buyPrice,
  shares: filledShares,
  voided = false,
  member,
  busy = false,
  error,
}: {
  stock: BetStock | null;
  grade: Grade;
  amount: number; // 만원
  onAmount: (v: number) => void;
  confirmed: boolean;
  onConfirm: () => void;
  onEdit: () => void;
  onCancel: () => void;
  /** 20:00 체결 뒤 — 금액·종목을 더는 못 바꾼다 */
  filled?: boolean;
  nxt?: boolean | null;
  /** "10/1" */
  dayLabel: string;
  /** 체결 뒤 — 20:00 종가와 주식 수 */
  buyPrice?: number | null;
  shares?: number | null;
  /** 종가로 한 주도 못 사서 판에서 빠졌다 */
  voided?: boolean;
  member: boolean;
  busy?: boolean;
  /** 서버가 돌려준 거절 이유 */
  error?: string;
}) {
  if (!member) {
    return (
      <div className="flex min-h-[280px] flex-col items-center justify-center gap-2.5 rounded-[20px] border-2 border-dashed border-zinc-800 p-6 text-center">
        <div className="mb-2.5 w-full">
          <Steps at={0} />
        </div>
        <span className="text-[15px] font-bold">로그인하면 걸 수 있어요</span>
        <span className="text-[13px] leading-relaxed text-zinc-400">가상 금액으로 하루 한 종목, 20:00 종가에 사요.</span>
        <button type="button" onClick={goLogin} className="mt-1 h-10 rounded-xl bg-zinc-100 px-5 text-[14px] font-semibold text-zinc-950 hover:bg-white">
          로그인
        </button>
      </div>
    );
  }

  if (!stock) {
    return (
      <div className="flex min-h-[280px] flex-col items-center justify-center gap-2.5 rounded-[20px] border-2 border-dashed border-zinc-800 p-6 text-center">
        <div className="mb-2.5 w-full">
          <Steps at={0} />
        </div>
        <span className="text-[15px] font-bold">종목을 하나 골라요</span>
        <span className="text-[13px] leading-relaxed text-zinc-400">
          고른 종목으로 오늘의 종베 티켓을 만들어요.
          <br />
          20:00 전까지 몇 번이든 바꿀 수 있어요.
        </span>
      </div>
    );
  }

  const shares = Math.floor((amount * 10000) / stock.price);
  const fill = ((amount - 100) / 99).toFixed(1);
  const sheen = grade === "S" || grade === "A";
  const notch: CSSProperties = { top: 118 };

  return (
    <div className="relative flex flex-col rounded-[20px] bg-zinc-900">
      {/* 절취 홈 — 머리 칸 위에 그려야 왼쪽도 티켓 모양대로 파인다 */}
      <span aria-hidden className="absolute -left-[11px] z-[2] h-5 w-5 rounded-full bg-zinc-950" style={notch} />
      <span aria-hidden className="absolute -right-[11px] z-[2] h-5 w-5 rounded-full bg-zinc-950" style={notch} />

      <div className="relative flex h-32 flex-col gap-1.5 overflow-hidden rounded-t-[20px] px-[22px] py-5" style={{ background: GRADE_TINT[grade] }}>
        {sheen && <span key={stock.code} aria-hidden className={grade === "A" ? "cb-sheen cb-sheen-a" : "cb-sheen"} />}
        <span className="flex justify-between text-xs text-zinc-400">
          <span>모의 종베 티켓 · {dayLabel}</span>
          <span className="num">{stock.code}</span>
        </span>
        <span className="text-[22px] font-bold tracking-tight">{stock.name}</span>
        <span className="flex items-center gap-2">
          <GradeAvatar grade={grade} />
          <span className="text-[13px] text-zinc-300">{GRADE_TEXT[grade]}</span>
          <span className={`num ml-auto text-[13px] ${colorByPnL(stock.chg)}`}>{pct(stock.chg)}</span>
        </span>
      </div>
      <div className="mx-4 h-0 border-t-2 border-dashed" style={{ borderColor: GRADE_PERF[grade] }} />

      <div className="flex flex-col gap-4 px-[22px] pb-[22px] pt-5">
        <Steps at={filled ? 1 : 0} nxt={nxt} />

        {!confirmed && !filled ? (
          <div className="flex flex-col gap-3">
            <span className="flex items-baseline justify-between">
              <span className="flex flex-col gap-0.5">
                <span className="text-[13px] text-zinc-400">얼마 걸까요?</span>
                <span className="text-[11px] text-zinc-500">가상 금액이에요</span>
              </span>
              <span className="num text-[28px] font-bold tracking-tight">{won(amount)}만원</span>
            </span>
            <label htmlFor="cb-amount" className="sr-only">
              걸 금액
            </label>
            <input
              id="cb-amount"
              className="cb-range"
              type="range"
              min={100}
              max={10000}
              step={100}
              value={amount}
              onChange={(e) => onAmount(Number(e.target.value))}
              aria-valuetext={`${won(amount)}만원`}
              style={{ background: `linear-gradient(90deg, #f1f3f5 0%, #f1f3f5 ${fill}%, #2a2f37 ${fill}%, #2a2f37 100%)` }}
            />
            <div className="num -mt-1.5 flex justify-between text-[11px] text-zinc-500">
              <span>100만</span>
              <span>5,000만</span>
              <span>1억</span>
            </div>
            <span className="flex justify-between text-[13px] text-zinc-400">
              <span>지금 가격이면</span>
              <span className="num text-zinc-100">약 {won(shares)}주</span>
            </span>
            <button
              type="button"
              onClick={onConfirm}
              disabled={shares === 0 || busy}
              className="h-12 rounded-xl bg-zinc-100 text-[15px] font-semibold text-zinc-950 transition-colors hover:bg-white disabled:cursor-not-allowed disabled:opacity-40"
            >
              {shares === 0 ? "금액이 부족해요" : busy ? "거는 중…" : "베팅하기"}
            </button>
          </div>
        ) : (
          <div className="flex flex-col gap-3.5">
            <div className="grid grid-cols-2 gap-2">
              <span className="flex flex-col gap-0.5">
                <span className="text-xs text-zinc-400">건 돈</span>
                <span className="num text-lg font-bold">{won(amount)}만원</span>
              </span>
              <span className="flex flex-col gap-0.5">
                <span className="text-xs text-zinc-400">{filled ? "매수가 · 20:00 종가" : "매수가"}</span>
                <span className="num text-lg font-bold">{filled && buyPrice ? `${won(buyPrice)}원` : "20:00에 정해져요"}</span>
              </span>
            </div>
            <span className="cb-stamp self-start rounded-[10px] border-[2.5px] border-red-400 px-3.5 py-1.5 text-[15px] font-bold tracking-[0.08em] text-red-400">
              {voided ? "체결 안 됨" : filled ? "체결 완료" : "베팅 완료"}
            </span>
            {!filled && (
            <div className="grid grid-cols-2 gap-1.5">
              <button type="button" onClick={onEdit} className="h-10 rounded-xl bg-zinc-850 text-[13px] font-semibold text-zinc-300 hover:text-zinc-100">
                금액 바꾸기
              </button>
              <button type="button" onClick={onCancel} className="h-10 rounded-xl bg-zinc-850 text-[13px] font-semibold text-zinc-300 hover:text-zinc-100">
                베팅 취소
              </button>
            </div>
            )}
          </div>
        )}
        {error && <span className="text-[13px] text-red-400">{error}</span>}
        <p className="m-0 text-xs leading-relaxed text-zinc-500">
          {voided
            ? "종가가 올라 이 금액으로는 한 주도 살 수 없었어요. 이 판에서는 빠져요."
            : filled
              ? `${won(filledShares ?? 0)}주 체결됐어요. 내일 아침 5분 중간가로 팔아요.`
              : "가상 금액이에요. 20:00 전까지 바꾸거나 취소할 수 있어요."}
        </p>
      </div>
    </div>
  );
}
