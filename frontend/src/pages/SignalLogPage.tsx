import { useEffect, useRef, useState } from "react";
import { useMe } from "../api/auth";
import LoginGate from "../components/common/LoginGate";
import { AnimatePresence, motion } from "motion/react";
import { useSignalEvents, useMarketSignalEvents, useLeadingStockDetail, useMarketCalendarStatus } from "../api/queries";
import type {
  SignalEventItem,
  SignalEventType,
  MarketSignalEventItem,
  MarketType,
  InvestorType,
} from "../types";
import { formatEok, formatFetchedAt, formatKoreanMoney, formatPct, formatPrice } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import PageHeader from "../components/layout/PageHeader";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import GoogleLoginButton from "../components/common/GoogleLoginButton";
import StockDetailPanel from "../components/common/StockDetailPanel";
import IndexDetailPanel from "../components/common/IndexDetailPanel";
import ListDetail, { useMobileDetail } from "../components/layout/ListDetail";
import DateNavigator, { todayStr } from "../components/common/DateNavigator";
import ChangeRateSelector, { CHANGE_RATE_OPTIONS } from "../components/common/ChangeRateSelector";

const MIN_RATE_KEY = "signalLog.minRate";

/** 시그널이 쌓이기 시작하는 시각 — NXT 프리마켓이 08:00에 연다. 그 전의 오늘은 빈 날이다. */
const SIGNAL_START_HOUR = 8;

/** 행 고유 키 — 같은 종목이 여러 행이어도 인덱스로 구분(방향키 행 단위 이동·열림 식별용). */
const rowKeyOf = (e: SignalEventItem, i: number) =>
  `${e.stockCode}-${e.eventType}-${e.occurredAt}-${i}`;

/** ISO LocalDateTime → HH:mm:ss (타임존 변환 없이 문자열에서 직접). */
function clockOf(iso: string): string {
  return iso.slice(11, 19);
}

/** 정규장(09:00~15:20) 시각은 흰색으로 강조, 장외(프리/애프터)는 회색. */
function clockClass(iso: string): string {
  const hm = iso.slice(11, 16); // HH:mm
  return hm >= "09:00" && hm <= "15:20" ? "text-zinc-100" : "text-zinc-500";
}

const EVENT_META: Record<SignalEventType, { label: string; chip: string }> = {
  VOLUME_SPIKE: { label: "스파이크", chip: "bg-rose-500/15 text-rose-300" },
  // 지지·저항 화면과 같은 색을 쓴다 — 주황=위(저항 방향), 하늘=아래(지지 방향).
  // 색이 화면마다 다른 방향을 뜻하면 읽는 사람이 매번 다시 배워야 한다.
  MA_REBOUND: { label: "반등", chip: "bg-orange-500/15 text-orange-400" },
  MA_BREAKDOWN: { label: "꺾임", chip: "bg-sky-500/15 text-sky-400" },
  // 생성 중단(2026-09-13). 과거 날짜 조회용으로만 남는다 — 흐린 톤으로 구분한다.
  BREAKOUT: { label: "돌파", chip: "bg-zinc-700/40 text-zinc-400" },
  BREAKOUT_IMMINENT: { label: "임박", chip: "bg-zinc-700/40 text-zinc-500" },
};

// 방향이 짝인 것끼리 한 탭으로 묶는다.
type TypeFilter = "ALL" | "MARKET" | "MA_GROUP" | SignalEventType;

/** 전이 유형 필터 탭 — 상세 패널 토글과 동일 디자인. */
const TYPE_TABS: { key: TypeFilter; label: string }[] = [
  { key: "ALL", label: "전체" },
  { key: "MA_GROUP", label: "반등 / 꺾임" },
  { key: "VOLUME_SPIKE", label: "스파이크" },
  { key: "MARKET", label: "지수" },
];

/** 유형 필터 매칭 — 묶음 탭은 짝을 이루는 두 유형을 함께 통과시킨다. */
function matchesType(eventType: SignalEventType, filter: TypeFilter): boolean {
  if (filter === "MA_GROUP") return eventType === "MA_REBOUND" || eventType === "MA_BREAKDOWN";
  return eventType === filter;
}

const MARKET_LABEL: Record<MarketType, string> = {
  KOSPI: "코스피",
  KOSDAQ: "코스닥",
};

const MARKET_CHIP: Record<MarketType, string> = {
  KOSPI: "bg-indigo-500/15 text-indigo-300",
  KOSDAQ: "bg-cyan-500/15 text-cyan-300",
};

const INVESTOR_LABEL: Record<InvestorType, string> = {
  FOREIGN: "외인",
  INSTITUTION: "기관",
  INDIVIDUAL: "개인",
};

/** 지수값 → 천 단위 쉼표 + 소수 2자리 (예: 2,653.81). */
function fmtIndex(v: number): string {
  return v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** 억원 → 사람이 읽기 쉬운 단위. 1조 이상은 "N조", 그 미만은 "N억". */
/** 실시간 로그 한 행 — 종목 시그널 또는 시장(코스피/코스닥) 시그널. at은 정렬용 발생 시각. */
type FeedRow =
  | { kind: "stock"; key: string; at: string; e: SignalEventItem }
  | { kind: "market"; key: string; at: string; m: MarketSignalEventItem };

/** 부호 포함 억원 표기 — 예: +2,615억 / -4,069억. 흐름 전환처럼 0을 넘나드는 값에 방향을 분명히. */
function signedEok(eok: number): string {
  return `${eok > 0 ? "+" : eok < 0 ? "-" : ""}${formatEok(Math.abs(eok))}`;
}

/** 지수 시그널 한 줄 요약 — 왼쪽 라벨/색, 오른쪽(순매수 금액). 행·여정에서 공용. */
function marketParts(m: MarketSignalEventItem) {
  const sideCls = m.side === "BUY" ? "text-red-400" : "text-blue-400";
  // accent=라벨을 방향색으로 강조(반등·꺾임처럼 투자자 순매수가 아닌 신호). netText=누적 칸에 표시할 금액.
  const netText: string | null = m.netAmountEok != null ? formatEok(Math.abs(m.netAmountEok)) : null;
  switch (m.kind) {
    case "MA_REBOUND":
      return { sideCls, accent: true, leftLabel: "반등", rightLabel: "", netText: null };
    case "MA_BREAKDOWN":
      return { sideCls, accent: true, leftLabel: "꺾임", rightLabel: "", netText: null };
    case "NET_FLOW_TURN":
      // 정점 → 전환 시점 누적 (예: 9,000억 → 8,000억). 정점 없는 과거 행은 전환 시점만.
      return {
        sideCls, accent: false,
        leftLabel: INVESTOR_LABEL[m.investor ?? "FOREIGN"],
        rightLabel: `${m.side === "BUY" ? "매수" : "매도"} 전환`,
        netText:
          m.extremeAmountEok != null && m.netAmountEok != null
            ? `${signedEok(m.extremeAmountEok)} → ${signedEok(m.netAmountEok)}`
            : netText,
      };
    default:
      return {
        sideCls, accent: false,
        leftLabel: INVESTOR_LABEL[m.investor ?? "FOREIGN"],
        rightLabel: `${formatEok(m.thresholdEok ?? 0)} ${m.side === "BUY" ? "순매수" : "순매도"}`,
        netText,
      };
  }
}

/**
 * 지수(코스피/코스닥) 시그널 한 행. kind=NET_BUY_LEVEL은 투자자 순매수 단계, MA_REBOUND는 지수 반등.
 * 누르면 그 시장의 그날 지수 시그널 여정을 펼치고, 우측에 지수 1분봉 차트를 띄운다.
 */
function renderMarketRow(
  key: string,
  m: MarketSignalEventItem,
  open: boolean,
  selected: boolean,
  onClick: () => void,
  journey: MarketSignalEventItem[],
  onShowDetail: () => void,
) {
  const { sideCls, accent, leftLabel, rightLabel, netText } = marketParts(m);
  // 종목 행의 종목명과 같은 자리 — 한 피드에 섞이므로 크기도 같이 간다
  const leftCls = accent ? `text-[13.5px] ${sideCls}` : "text-[13.5px] text-zinc-300";
  return (
    <motion.li
      key={key}
      data-row-key={key}
      layout
      initial={{ opacity: 0, y: -6 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.2 }}
    >
      <button
        type="button"
        onClick={onClick}
        className={`flex w-full flex-col gap-1 rounded-xl px-2.5 py-2.5 text-left transition-colors hover:bg-zinc-850 lg:px-3.5 ${
          selected ? "bg-selected hover:bg-selected" : ""
        }`}
      >
        <span className="flex w-full items-center gap-2">
          <span className={`num w-[3.75rem] shrink-0 text-xs ${clockClass(m.occurredAt)}`}>{clockOf(m.occurredAt)}</span>
          <span className={`shrink-0 rounded px-1.5 py-0.5 text-[11px] ${MARKET_CHIP[m.market]}`}>
            {MARKET_LABEL[m.market]}
          </span>
          <span className={leftCls}>{leftLabel}</span>
          <span className="ml-auto flex shrink-0 items-baseline gap-2.5">
            <span className="num text-[13.5px] text-zinc-100">{m.indexValue != null ? fmtIndex(m.indexValue) : ""}</span>
            <span className="min-w-14 whitespace-nowrap text-right">
              {m.changeRate != null && (
                <ProfitText value={m.changeRate / 100} format={formatPct} className="num text-xs" />
              )}
            </span>
          </span>
        </span>
        {(rightLabel || netText) && (
          <span className="num truncate pl-[4.25rem] text-xs">
            {rightLabel && <span className={sideCls}>{rightLabel}</span>}
            {netText && <span className="text-zinc-500">{rightLabel ? " · " : ""}누적 {netText}</span>}
          </span>
        )}
      </button>

      {open && (
        <div className="mx-0.5 mb-1.5 mt-0.5 rounded-xl bg-zinc-900 px-4 pb-4 pt-3">
          {/* 폰에서는 상세 칸이 접혀 행 높이가 들쭉날쭉해진다 — 접지 말고 옆으로 밀어 본다.
              `-mx-4 px-4`는 스크롤 영역을 카드 가장자리까지 넓혀 잘린 글자가 여백에서 끊기지 않게 한다. */}
          <div className="-mx-4 overflow-x-auto px-4">
            <table className="w-full min-w-[26rem] whitespace-nowrap text-xs num border-separate border-spacing-y-0.5">
              <thead className="text-zinc-600">
                <tr>
                  <th className="text-left font-medium pb-1">시각</th>
                  <th className="text-left font-medium pb-1">시그널</th>
                  <th className="text-right font-medium pb-1">누적</th>
                  <th className="text-right font-medium pb-1">지수</th>
                  <th className="text-right font-medium pb-1">등락</th>
                </tr>
              </thead>
              <tbody>
                {journey.map((j, k) => {
                  const p = marketParts(j);
                  return (
                    <tr key={`${j.kind}-${j.occurredAt}-${k}`}>
                      <td className="text-zinc-500 py-0.5">{clockOf(j.occurredAt)}</td>
                      <td>
                        <span className={`font-semibold ${p.sideCls}`}>{p.leftLabel}</span>
                        {p.rightLabel && <span className={p.sideCls}> {p.rightLabel}</span>}
                      </td>
                      <td className="text-right text-zinc-400">
                        {p.netText ?? "—"}
                      </td>
                      <td className="text-right text-zinc-400">
                        {j.indexValue != null ? fmtIndex(j.indexValue) : ""}
                      </td>
                      <td className="text-right">
                        {j.changeRate != null ? (
                          <ProfitText value={j.changeRate / 100} format={formatPct} className="num" />
                        ) : (
                          ""
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {onShowDetail && <ShowDetailButton label="지수 차트 보기" onClick={onShowDetail} />}
        </div>
      )}
    </motion.li>
  );
}

/**
 * 이벤트별 핵심 수치 한 줄. 돌파선 가격은 그때의 현재가×(1+갭/100)으로 역산.
 * 스파이크는 배율+그 분봉 거래대금(rose)과 그 순간 누적 거래대금(흐리게)을 함께 보인다.
 */
function detailOf(e: SignalEventItem) {
  if (e.eventType === "VOLUME_SPIKE") {
    if (!e.spikeRatio) return "";
    const dirCls =
      e.spikeDirection === "BUY" ? "text-red-400" : e.spikeDirection === "SELL" ? "text-blue-400" : "text-zinc-500";
    const dirLabel =
      e.spikeDirection === "BUY" ? "매수" : e.spikeDirection === "SELL" ? "매도" : e.spikeDirection === "FLAT" ? "보합" : "";
    return (
      <>
        <span className="text-rose-300">
          {e.spikeRatio.toFixed(1)}배
          {e.minuteTradingValue != null && ` ${formatKoreanMoney(e.minuteTradingValue)}`}
        </span>
        {dirLabel && <span className={dirCls}> {dirLabel}</span>}
        <span className="text-zinc-500"> · 누적 {formatKoreanMoney(e.tradingValue)}</span>
      </>
    );
  }
  if (e.eventType === "MA_REBOUND" || e.eventType === "MA_BREAKDOWN") {
    // 칩·점과 같은 색을 쓴다 — 주황=반등(위), 하늘=꺾임(아래)
    const up = e.eventType === "MA_REBOUND";
    return (
      <span className={up ? "text-orange-400" : "text-sky-300"}>
        5분 20이평{e.ma != null && ` ${formatPrice(e.ma)}원`}{" "}
        {up ? "상향돌파" : "하향이탈"}
      </span>
    );
  }
  if (e.gapRate == null) return e.eventType === "BREAKOUT" ? "전고 돌파" : "";
  const line = Math.round(e.currentPrice * (1 + e.gapRate / 100));
  if (e.eventType === "BREAKOUT") return `${formatPrice(line)}원 돌파`;
  return `${formatPrice(line)}원 돌파까지 ${formatPrice(line - e.currentPrice)}원 (${e.gapRate.toFixed(2)}%) 남음`;
}

function Stat({
  label,
  value,
  valueClass = "text-zinc-100",
}: {
  label: string;
  value: React.ReactNode;
  valueClass?: string;
}) {
  return (
    <div className="rounded-xl bg-elevated px-3 py-2">
      {/* bg-elevated가 카드보다 밝아 zinc-500이면 라벨이 바탕에 묻힌다 */}
      <div className="text-xs text-zinc-400">{label}</div>
      <div className={`num text-base font-bold ${valueClass}`}>{value}</div>
    </div>
  );
}

/**
 * 종목 여정 — 펼친 종목의 그날 시그널 경로. 상단 요약 스탯 4개(누적 거래대금·매수/매도 스파이크 횟수·최대 스파이크
 * 거래대금·주도주 조건) + 시간순 테이블. [journey]는 최신순으로 들어오고, 테이블도 최신→오래된으로 그대로 그린다.
 */
function StockJourney({
  stockCode,
  journey,
  onShowDetail,
}: {
  stockCode: string;
  journey: SignalEventItem[];
  /** 좁은 화면에서 종목 상세로 넘어가는 버튼 — 넓으면 상세가 이미 옆에 있다 */
  onShowDetail?: () => void;
}) {
  const detailQ = useLeadingStockDetail(stockCode);
  const filters = detailQ.data?.filterResults;
  const passed = filters?.filter((f) => f.passed).length;

  // 스파이크가 어느 쪽으로 터졌는지 — 보합은 방향이 없어 어느 쪽에도 안 센다
  const spikes = journey.filter((j) => j.eventType === "VOLUME_SPIKE");
  const buySpikes = spikes.filter((j) => j.spikeDirection === "BUY").length;
  const sellSpikes = spikes.filter((j) => j.spikeDirection === "SELL").length;
  const spikeValues = journey
    .filter((j) => j.eventType === "VOLUME_SPIKE" && j.minuteTradingValue != null)
    .map((j) => j.minuteTradingValue as number);
  const maxSpike = spikeValues.length > 0 ? Math.max(...spikeValues) : null;
  const accTradingValue = journey[0]?.tradingValue ?? 0;
  const ordered = journey; // 최신 → 오래된

  return (
    <div className="mx-0.5 mb-1.5 mt-0.5 rounded-xl bg-zinc-900 px-4 pb-4 pt-3">
      <div className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-2 2xl:grid-cols-4">
        <Stat label="누적 거래대금" value={formatKoreanMoney(accTradingValue)} />
        <Stat
          label="매수 · 매도 스파이크"
          value={
            <>
              <span className="text-red-400">{buySpikes}</span>
              <span className="text-zinc-600"> · </span>
              <span className="text-blue-400">{sellSpikes}</span>
            </>
          }
        />
        <Stat
          label="최대 스파이크"
          value={maxSpike != null ? formatKoreanMoney(maxSpike) : "—"}
          valueClass="text-rose-300"
        />
        <Stat
          label="주도주 조건"
          value={
            filters ? (
              <>
                <span className="text-emerald-400">{passed}</span> / {filters.length}
              </>
            ) : (
              "…"
            )
          }
        />
      </div>
      {/* 폰에서는 상세 칸이 접혀 행 높이가 들쭉날쭉해진다 — 접지 말고 옆으로 밀어 본다.
          `-mx-4 px-4`는 스크롤 영역을 카드 가장자리까지 넓혀 잘린 글자가 여백에서 끊기지 않게 한다. */}
      <div className="-mx-4 overflow-x-auto px-4">
        <table className="w-full min-w-[26rem] whitespace-nowrap text-xs num border-separate border-spacing-y-0.5">
          <thead className="text-zinc-600">
            <tr>
              <th className="text-left font-medium pb-1">시각</th>
              <th className="text-left font-medium pb-1">유형</th>
              <th className="text-left font-medium pb-1">상세</th>
              <th className="text-right font-medium pb-1">가격</th>
            </tr>
          </thead>
          <tbody>
            {ordered.map((j, k) => {
              const jm = EVENT_META[j.eventType];
              return (
                <tr key={`${j.eventType}-${j.occurredAt}-${k}`}>
                  <td className="text-zinc-500 py-0.5">{clockOf(j.occurredAt)}</td>
                  <td>
                    <span className={`text-[11px] font-semibold px-1.5 py-0.5 rounded ${jm.chip}`}>
                      {jm.label}
                    </span>
                  </td>
                  <td className="text-zinc-300">{detailOf(j)}</td>
                  <td className="text-right text-zinc-400">{formatPrice(j.currentPrice)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {onShowDetail && <ShowDetailButton label="종목 상세 보기" onClick={onShowDetail} />}
    </div>
  );
}

/** 여정 아래 — 좁은 화면에서만 보이는 "상세로" 버튼. */
function ShowDetailButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="mt-3 flex w-full items-center justify-center gap-0.5 rounded-xl bg-zinc-850 py-2.5 text-[13px] font-medium text-zinc-100 lg:hidden"
    >
      {label}
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="text-zinc-500">
        <path d="M9 6l6 6-6 6" />
      </svg>
    </button>
  );
}

/** YYYY-MM-DD → "9월 23일" */
function dayLabel(iso: string): string {
  const [, m, d] = iso.split("-").map(Number);
  return `${m}월 ${d}일`;
}

/**
 * 미리보기 끝 — 아래로 더 있다는 걸 보이고 로그인으로 잇는다.
 *
 * 가짜 행이다. 진짜 행을 흐리게 깔면 그 데이터가 브라우저까지 내려와야 하므로
 * 차단이 아니라 가리기가 된다(개발자도구로 읽힌다). 서버가 자른 뒤라 여기엔 채울 내용이 없다.
 */
function PreviewGate({ shown, total, date }: { shown: number; total: number; date: string }) {
  return (
    <li className="relative">
      {/* 아래로 갈수록 지워진다 — 줄마다 농도를 주면 계단이 생겨 "이어진다"가 덜 읽힌다.
          티커 뷰포트(index.css)와 같은 마스크 방식이다. */}
      <div
        aria-hidden
        className="select-none opacity-60"
        style={{
          maskImage: "linear-gradient(#000, transparent)",
          WebkitMaskImage: "linear-gradient(#000, transparent)",
        }}
      >
        {[0, 1, 2].map((i) => (
          <div key={i} className="flex items-center gap-3 px-4 py-3.5">
            <span className="h-3 w-14 rounded bg-zinc-700" />
            <span className="h-4 w-11 rounded bg-zinc-700" />
            <span className="h-3.5 w-28 rounded bg-zinc-700" />
            <span className="ml-auto h-3 w-16 rounded bg-zinc-700" />
          </div>
        ))}
      </div>
      <div className="absolute inset-0 flex flex-col items-center justify-center gap-2.5">
        {/* 날짜를 옮겨 볼 수 있어 "오늘"로 못 박지 않는다.
            숫자에만 mono를 건다 — 한글까지 걸면 한 줄 안에서 글꼴이 갈린다 */}
        <p className="text-xs text-zinc-400">
          {date === todayStr() ? "오늘" : dayLabel(date)} <span className="num">{total}</span>건 중 <span className="num">{shown}</span>건을 보고 있습니다
        </p>
        <GoogleLoginButton />
      </div>
    </li>
  );
}

function SignalLogPageInner({ authenticated }: { authenticated: boolean }) {
  const [date, setDate] = useState(todayStr());
  // 처음 열 때는 가장 최근 거래일 — 휴장일이나 프리마켓(08:00) 전에 오늘을 열면 빈 목록뿐이다.
  // 사용자가 날짜를 한 번 고른 뒤에는 건드리지 않는다.
  const krCalendar = useMarketCalendarStatus("KR").data;
  const datePicked = useRef(false);
  useEffect(() => {
    if (datePicked.current || !krCalendar?.previousOpenDay) return;
    const beforeSession = new Date().getHours() < SIGNAL_START_HOUR;
    if (krCalendar.isHoliday || beforeSession) setDate(krCalendar.previousOpenDay);
  }, [krCalendar]);
  // 발생 시점 등락률 하한 — 행 표시 필터. 새로고침해도 유지(localStorage), 기본 0%.
  const [minRate, setMinRate] = useState(() => {
    const raw = localStorage.getItem(MIN_RATE_KEY);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : 0;
  });
  useEffect(() => {
    localStorage.setItem(MIN_RATE_KEY, String(minRate));
  }, [minRate]);
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("ALL"); // 전이 유형 필터
  const eventsQ = useSignalEvents(date);
  const marketQ = useMarketSignalEvents(date, authenticated);
  const data = eventsQ.data;
  const allEvents = data?.events ?? [];
  // 종목 시그널 — 등락률 하한 + 유형 필터. "시장"/특정 유형 선택 시 종목 행은 빠진다.
  // 미로그인은 필터 조작이 막혀 있다 — 저장돼 있던 값이 미리보기를 조용히 깎지 않게 무시한다
  const events = authenticated
    ? allEvents.filter(
        (e) => e.priceChangeRate >= minRate && (typeFilter === "ALL" || matchesType(e.eventType, typeFilter)),
      )
    : allEvents;
  const allMarketEvents = marketQ.data?.events ?? []; // 여정용 — 필터 무관 전체
  // 시장 시그널 — 전체/지수 탭에서만 노출(등락률 필터 무관).
  const marketEvents = typeFilter === "ALL" || typeFilter === "MARKET" ? allMarketEvents : [];
  // 종목·시장 행을 시각 내림차순으로 병합한 렌더용 피드. 방향키·여정은 종목 행(events)에만 적용.
  const feed: FeedRow[] = [
    ...events.map((e, i): FeedRow => ({ kind: "stock", key: rowKeyOf(e, i), at: e.occurredAt, e })),
    ...marketEvents.map(
      (m, i): FeedRow => ({
        kind: "market",
        key: `m-${m.market}-${m.investor}-${m.side}-${m.occurredAt}-${i}`,
        at: m.occurredAt,
        m,
      }),
    ),
  ].sort((a, b) => (a.at < b.at ? 1 : a.at > b.at ? -1 : 0));
  const [openKey, setOpenKey] = useState<string | null>(null);
  const mobile = useMobileDetail();

  // 우측 패널 선택 — 종목(차트) 또는 지수(시장 차트). 둘 중 하나만 활성.
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [selectedMarket, setSelectedMarket] = useState<MarketType | null>(null);
  const [selectedMarketAt, setSelectedMarketAt] = useState<string | null>(null); // 선택한 지수 시그널 발생 시각
  useEffect(() => {
    // 첫 로드 시 최신 종목 자동 선택 — 단, 사용자가 지수를 고른 상태면 건드리지 않는다.
    if (!authenticated) return;
    if (selectedCode === null && selectedMarket === null && events.length > 0) {
      setSelectedCode(events[0].stockCode);
    }
  }, [authenticated, events, selectedCode, selectedMarket]);

  // ↑/↓ 방향키로 행 단위 이동 — 같은 종목이 여러 행이어도 각 행을 거친다. 그 행을 열고 차트도 갱신.
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      if (!authenticated) return;
      if (ev.key !== "ArrowDown" && ev.key !== "ArrowUp") return;
      const tag = (ev.target as HTMLElement | null)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      if (events.length === 0) return;
      ev.preventDefault();
      const keys = events.map((e, i) => rowKeyOf(e, i));
      const cur = openKey ? keys.indexOf(openKey) : -1;
      const next =
        ev.key === "ArrowDown"
          ? Math.min((cur < 0 ? -1 : cur) + 1, keys.length - 1)
          : Math.max((cur < 0 ? keys.length : cur) - 1, 0);
      setOpenKey(keys[next]);
      setSelectedCode(events[next].stockCode);
      setSelectedMarket(null);
      document.querySelector(`[data-row-key="${keys[next]}"]`)?.scrollIntoView({ block: "nearest" });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [authenticated, events, openKey]);

  const list = (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-3 lg:px-3.5">
        <PageHeader
          title="주도주 시그널"
          count={data || marketQ.data ? feed.length : undefined}
          queriedAt={formatFetchedAt(eventsQ.dataUpdatedAt)}
          loading={eventsQ.isFetching}
          trailing={
            <DateNavigator
              date={date}
              onChange={(d) => {
                datePicked.current = true;
                setDate(d);
                setSelectedCode(null);
                setSelectedMarket(null);
              }}
            />
          }
        />
        {/* 유형 필터와 등락률 — 미로그인은 조작이 잠겨 아예 감춘다 */}
        {authenticated && (
          <div className="flex flex-col gap-2">
            <div className="flex w-fit rounded-xl bg-zinc-800 p-0.5 text-xs">
              {TYPE_TABS.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setTypeFilter(t.key)}
                  className={`rounded-lg px-2.5 py-1.5 transition-colors ${
                    typeFilter === t.key ? "bg-elevated font-medium text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>
            <div className="flex justify-end">
              <ChangeRateSelector value={minRate} onChange={setMinRate} />
            </div>
          </div>
        )}
      </div>

      {eventsQ.isLoading || marketQ.isLoading ? (
        <div className="space-y-3 p-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : feed.length === 0 ? (
        <EmptyState message={`${date} 시그널이 없습니다`} hint="국내 종목, 지수 시그널을 조회합니다." />
      ) : (
        <ul className="flex flex-col gap-0.5">
          <AnimatePresence initial={false}>
            {feed.map((row) => {
              if (row.kind === "market") {
                const m = row.m;
                const open = openKey === row.key;
                const selected = selectedMarket === m.market && selectedCode === null;
                const journey = open ? allMarketEvents.filter((x) => x.market === m.market) : [];
                return renderMarketRow(
                  row.key,
                  m,
                  open,
                  selected,
                  () => {
                    setSelectedMarket(m.market);
                    setSelectedMarketAt(m.occurredAt);
                    setSelectedCode(null);
                    setOpenKey(open ? null : row.key);
                  },
                  journey,
                  mobile.show,
                );
              }
              const e = row.e;
              const rowKey = row.key;
              const meta = EVENT_META[e.eventType];
              const open = openKey === rowKey;
              // 같은 종목 이벤트 모음(피드·여정 모두 최신순). 여정은 필터와 무관하게 전체 경로를 보여준다
              const journey = open ? allEvents.filter((x) => x.stockCode === e.stockCode) : [];
              return (
                <motion.li
                  key={rowKey}
                  data-row-key={rowKey}
                  layout
                  initial={{ opacity: 0, y: -6 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0 }}
                  transition={{ duration: 0.2 }}
                >
                  {/* 미로그인은 펼치지 않는다 — 여정이 로그인 전용 상세를 부른다 */}
                  <button
                    type="button"
                    disabled={!authenticated}
                    onClick={() => {
                      setSelectedCode(e.stockCode);
                      setSelectedMarket(null);
                      setOpenKey(open ? null : rowKey);
                    }}
                    className={`flex w-full flex-col gap-1 rounded-xl px-2.5 py-2.5 text-left transition-colors lg:px-3.5 ${
                      authenticated ? "hover:bg-zinc-850" : "cursor-default"
                    } ${e.stockCode === selectedCode ? "bg-selected hover:bg-selected" : ""}`}
                  >
                    {/* 윗줄: 시각·유형·종목 / 현재가·등락률 */}
                    <span className="flex w-full items-center gap-2">
                      <span className={`num w-[3.75rem] shrink-0 text-xs ${clockClass(e.occurredAt)}`}>
                        {clockOf(e.occurredAt)}
                      </span>
                      <span className={`shrink-0 rounded px-1.5 py-0.5 text-[11px] ${meta.chip}`}>{meta.label}</span>
                      <span className="min-w-0 truncate text-[13.5px] text-zinc-100">{e.stockName}</span>
                      <span className="ml-auto flex shrink-0 items-baseline gap-2.5">
                        <span className="num text-[13.5px] text-zinc-100">{formatPrice(e.currentPrice)}</span>
                        <span className="min-w-14 whitespace-nowrap text-right">
                          <ProfitText value={e.priceChangeRate / 100} format={formatPct} className="num text-xs" />
                        </span>
                      </span>
                    </span>
                    {/* 아랫줄: 무엇이 일어났는지 — 한 줄에 다 넣으면 좁을 때 접혀 행 높이가 들쭉날쭉해진다 */}
                    <span className="num truncate pl-[4.25rem] text-xs text-zinc-300">{detailOf(e)}</span>
                  </button>

                  {open && <StockJourney stockCode={e.stockCode} journey={journey} onShowDetail={mobile.show} />}
                </motion.li>
              );
            })}
          </AnimatePresence>
          {/* totalCount는 서버가 자르기 전 전체 건수다 — 그린 행 수보다 크면 잘린 것이다 */}
          {!authenticated && (data?.totalCount ?? 0) > feed.length && (
            <PreviewGate shown={feed.length} total={data?.totalCount ?? 0} date={date} />
          )}
        </ul>
      )}
    </div>
  );

  const detail = !authenticated ? (
    <LoginGate
      title="종목 상세"
      description="주도주 조건·분봉·일봉·투자자 수급을 시그널이 뜬 종목별로 봅니다. 로그인 후 확인할 수 있습니다."
    />
  ) : selectedMarket ? (
    <div className="flex flex-col gap-4">
      <BackToList onBack={mobile.hide} />
      <IndexDetailPanel
        market={selectedMarket}
        at={selectedMarketAt}
        changeRate={allMarketEvents.find((m) => m.market === selectedMarket)?.changeRate ?? null}
      />
    </div>
  ) : (
    <StockDetailPanel stockCode={selectedCode} date={date} onBack={mobile.hide} />
  );

  return <ListDetail list={list} detail={detail} detailOpen={mobile.open} />;
}

/** 모바일 상세 맨 위 "목록" — 지수 상세는 종목 상세와 달리 자체 버튼이 없다. */
function BackToList({ onBack }: { onBack: () => void }) {
  return (
    <button
      type="button"
      onClick={onBack}
      className="-ml-1 flex w-fit items-center gap-0.5 text-sm text-zinc-400 hover:text-zinc-200 lg:hidden"
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M15 6l-6 6 6 6" />
      </svg>
      목록
    </button>
  );
}


/** 미로그인도 최신 몇 건은 본다 — 서버가 잘라서 내려준다(`PREVIEW_COUNT`).
 *  날짜 이동은 열려 있다(어느 날이든 서버가 같은 규칙으로 자른다). 필터·지수 시그널·종목 상세는 로그인 뒤다. */
export default function SignalLogPage() {
  const { data: me, isLoading } = useMe();
  if (isLoading) return null;
  return <SignalLogPageInner authenticated={!!me?.authenticated} />;
}
