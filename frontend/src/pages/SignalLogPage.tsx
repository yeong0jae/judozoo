import { useEffect, useState } from "react";
import { useMe } from "../api/auth";
import LoginGate from "../components/common/LoginGate";
import { AnimatePresence, motion } from "motion/react";
import { useSignalEvents, useMarketSignalEvents, useLeadingStockDetail } from "../api/queries";
import type {
  SignalEventItem,
  SignalEventType,
  MarketSignalEventItem,
  MarketType,
  InvestorType,
} from "../types";
import { formatEok, formatKoreanMoney, formatPct, formatPrice } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import HolidayBanner from "../components/common/HolidayBanner";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import StockAvatar from "../components/common/StockAvatar";
import StockDetailPanel from "../components/common/StockDetailPanel";
import IndexDetailPanel from "../components/common/IndexDetailPanel";
import DateNavigator, { todayStr } from "../components/common/DateNavigator";
import ChangeRateSelector, { CHANGE_RATE_OPTIONS } from "../components/common/ChangeRateSelector";

const MIN_RATE_KEY = "signalLog.minRate";

/** 행 고유 키 — 같은 종목이 여러 행이어도 인덱스로 구분(방향키 행 단위 이동·열림 식별용). */
const rowKeyOf = (e: SignalEventItem, i: number) =>
  `${e.stockCode}-${e.eventType}-${e.occurredAt}-${i}`;

/** 키움 마스터 코드 — "009150_AL" 같이 거래소 접미사가 붙으면 앞쪽 6자리만. */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

/** ISO LocalDateTime → HH:mm:ss (타임존 변환 없이 문자열에서 직접). */
function clockOf(iso: string): string {
  return iso.slice(11, 19);
}

/** 정규장(09:00~15:20) 시각은 흰색으로 강조, 장외(프리/애프터)는 회색. */
function clockClass(iso: string): string {
  const hm = iso.slice(11, 16); // HH:mm
  return hm >= "09:00" && hm <= "15:20" ? "text-zinc-100" : "text-zinc-500";
}

const EVENT_META: Record<SignalEventType, { label: string; chip: string; dot: string }> = {
  VOLUME_SPIKE: { label: "스파이크", chip: "bg-rose-500/15 text-rose-300", dot: "bg-rose-400" },
  // 지지·저항 화면과 같은 색을 쓴다 — 주황=위(저항 방향), 하늘=아래(지지 방향).
  // 색이 화면마다 다른 방향을 뜻하면 읽는 사람이 매번 다시 배워야 한다.
  MA_REBOUND: { label: "반등", chip: "bg-orange-500/15 text-orange-400", dot: "bg-orange-400" },
  MA_BREAKDOWN: { label: "꺾임", chip: "bg-sky-500/15 text-sky-400", dot: "bg-sky-400" },
  // 생성 중단(2026-09-13). 과거 날짜 조회용으로만 남는다 — 흐린 톤으로 구분한다.
  BREAKOUT: { label: "돌파", chip: "bg-zinc-700/40 text-zinc-400", dot: "bg-zinc-500" },
  BREAKOUT_IMMINENT: { label: "임박", chip: "bg-zinc-700/40 text-zinc-500", dot: "bg-zinc-600" },
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
) {
  const dot = m.market === "KOSPI" ? "bg-indigo-400" : "bg-cyan-400";
  const { sideCls, accent, leftLabel, rightLabel, netText } = marketParts(m);
  const leftCls = accent ? `text-sm font-semibold ${sideCls}` : "text-sm font-semibold text-zinc-100";
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
        className={`w-full flex items-center flex-wrap gap-x-3 gap-y-1 px-4 py-3 text-left rounded-xl hover:bg-white/[0.03] transition-colors ${
          selected ? "bg-emerald-900/30" : ""
        }`}
      >
        <div className="flex items-center gap-2 min-w-0 flex-1">
          <span className={`num text-xs tabular-nums w-16 shrink-0 ${clockClass(m.occurredAt)}`}>
            {clockOf(m.occurredAt)}
          </span>
          <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${dot}`} />
          <span className={`text-xs font-semibold px-1.5 py-0.5 rounded shrink-0 ${MARKET_CHIP[m.market]}`}>
            {MARKET_LABEL[m.market]}
          </span>
          <span className={leftCls}>{leftLabel}</span>
        </div>
        <div className="flex items-center gap-3 shrink-0 ml-auto pl-[4.5rem] md:pl-0">
          {rightLabel && <span className={`num text-xs font-semibold ${sideCls}`}>{rightLabel}</span>}
          {netText && (
            <span className="num text-xs text-zinc-500">누적 {netText}</span>
          )}
          <span className="num text-xs text-zinc-100 w-20 text-right">
            {m.indexValue != null ? fmtIndex(m.indexValue) : ""}
          </span>
          <span className="w-16 text-right">
            {m.changeRate != null && (
              <ProfitText value={m.changeRate / 100} format={formatPct} className="num text-xs" />
            )}
          </span>
        </div>
      </button>

      {open && (
        <div className="px-4 pb-4 pt-3 bg-white/[0.02]">
          <table className="w-full text-xs num border-separate border-spacing-y-0.5">
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
          🔥{e.spikeRatio.toFixed(1)}배
          {e.minuteTradingValue != null && ` ${formatKoreanMoney(e.minuteTradingValue)}`}
        </span>
        {dirLabel && <span className={dirCls}> {dirLabel}</span>}
        <span className="text-zinc-500"> · 누적 {formatKoreanMoney(e.tradingValue)}</span>
      </>
    );
  }
  if (e.eventType === "MA_REBOUND" || e.eventType === "MA_BREAKDOWN") {
    return (
      <span className="text-sky-300">
        1분 60이평{e.ma != null && ` ${formatPrice(e.ma)}원`}{" "}
        {e.eventType === "MA_REBOUND" ? "상향돌파" : "하향이탈"}
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
    <div className="rounded-xl bg-white/[0.03] px-3 py-2">
      <div className="text-xs text-zinc-500">{label}</div>
      <div className={`num text-base font-bold ${valueClass}`}>{value}</div>
    </div>
  );
}

/**
 * 종목 여정 — 펼친 종목의 그날 시그널 경로. 상단 요약 스탯 4개(누적 거래대금·돌파 횟수·최대 스파이크
 * 거래대금·필터 충족) + 시간순 테이블. [journey]는 최신순으로 들어오고, 테이블도 최신→오래된으로 그대로 그린다.
 */
function StockJourney({ stockCode, journey }: { stockCode: string; journey: SignalEventItem[] }) {
  const detailQ = useLeadingStockDetail(stockCode);
  const filters = detailQ.data?.filterResults;
  const passed = filters?.filter((f) => f.passed).length;

  const breakouts = journey.filter((j) => j.eventType === "BREAKOUT").length;
  const spikeValues = journey
    .filter((j) => j.eventType === "VOLUME_SPIKE" && j.minuteTradingValue != null)
    .map((j) => j.minuteTradingValue as number);
  const maxSpike = spikeValues.length > 0 ? Math.max(...spikeValues) : null;
  const accTradingValue = journey[0]?.tradingValue ?? 0;
  const ordered = journey; // 최신 → 오래된

  return (
    <div className="px-4 pb-4 pt-3 bg-white/[0.02]">
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 mb-3">
        <Stat label="누적 거래대금" value={formatKoreanMoney(accTradingValue)} />
        <Stat label="오늘 돌파" value={`${breakouts}회`} valueClass="text-emerald-400" />
        <Stat
          label="최대 스파이크"
          value={maxSpike != null ? formatKoreanMoney(maxSpike) : "—"}
          valueClass="text-rose-300"
        />
        <Stat
          label="필터 충족"
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
      <table className="w-full text-xs num border-separate border-spacing-y-0.5">
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
  );
}

function SignalLogPageInner() {
  const [date, setDate] = useState(todayStr());
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
  const marketQ = useMarketSignalEvents(date);
  const data = eventsQ.data;
  const allEvents = data?.events ?? [];
  // 종목 시그널 — 등락률 하한 + 유형 필터. "시장"/특정 유형 선택 시 종목 행은 빠진다.
  const events = allEvents.filter(
    (e) => e.priceChangeRate >= minRate && (typeFilter === "ALL" || matchesType(e.eventType, typeFilter)),
  );
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

  // 우측 패널 선택 — 종목(차트) 또는 지수(시장 차트). 둘 중 하나만 활성.
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [selectedMarket, setSelectedMarket] = useState<MarketType | null>(null);
  const [selectedMarketAt, setSelectedMarketAt] = useState<string | null>(null); // 선택한 지수 시그널 발생 시각
  useEffect(() => {
    // 첫 로드 시 최신 종목 자동 선택 — 단, 사용자가 지수를 고른 상태면 건드리지 않는다.
    if (selectedCode === null && selectedMarket === null && events.length > 0) {
      setSelectedCode(events[0].stockCode);
    }
  }, [events, selectedCode, selectedMarket]);

  // ↑/↓ 방향키로 행 단위 이동 — 같은 종목이 여러 행이어도 각 행을 거친다. 그 행을 열고 차트도 갱신.
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
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
  }, [events, openKey]);

  return (
    <div className="space-y-4">
      <HolidayBanner />
      <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
        <div>
          <h2 className="text-xl font-bold flex items-center gap-2">
            주도주 시그널
            <span
              className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
                eventsQ.isFetching ? "animate-ping" : "animate-pulse"
              }`}
            />
          </h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            선택 날짜의 돌파·임박·스파이크·지수 전이 · 행을 누르면 그 종목의 여정
          </p>
        </div>
        <div className="flex items-center gap-3">
          {(data || marketQ.data) && (
            <span className="text-xs text-zinc-300 font-medium">{feed.length}건</span>
          )}
          <DateNavigator
            date={date}
            onChange={(d) => {
              setDate(d);
              setSelectedCode(null);
              setSelectedMarket(null);
            }}
          />
        </div>
      </div>

      {/* 등락률 필터는 목록 컬럼(50%) 폭에 맞춰 우측 정렬 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="flex justify-end pb-2 border-b border-white/[0.08]">
          <ChangeRateSelector value={minRate} onChange={setMinRate} />
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
      <section>
        {/* 유형 필터 — 리스트 위 한 줄 */}
        <div className="py-2.5">
          <div className="flex rounded-lg bg-white/[0.04] p-0.5 text-xs w-fit">
            {TYPE_TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => setTypeFilter(t.key)}
                className={`px-2.5 py-1 rounded-md transition-colors ${
                  typeFilter === t.key ? "bg-white/[0.1] text-zinc-100" : "text-zinc-500 hover:text-zinc-300"
                }`}
              >
                {t.label}
              </button>
            ))}
          </div>
        </div>
        {eventsQ.isLoading || marketQ.isLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : feed.length === 0 ? (
          <EmptyState message={`${date} 시그널이 없습니다`} />
        ) : (
          <ul className="divide-y divide-white/[0.04]">
            <AnimatePresence initial={false}>
              {feed.map((row) => {
                if (row.kind === "market") {
                  const m = row.m;
                  const open = openKey === row.key;
                  const selected = selectedMarket === m.market && selectedCode === null;
                  const journey = open ? allMarketEvents.filter((x) => x.market === m.market) : [];
                  return renderMarketRow(row.key, m, open, selected, () => {
                    setSelectedMarket(m.market);
                    setSelectedMarketAt(m.occurredAt);
                    setSelectedCode(null);
                    setOpenKey(open ? null : row.key);
                  }, journey);
                }
                const e = row.e;
                const rowKey = row.key;
                const code = shortCode(e.stockCode);
                const meta = EVENT_META[e.eventType];
                const open = openKey === rowKey;
                // 같은 종목 이벤트 모음(피드·여정 모두 최신순). 여정은 필터와 무관하게 전체 경로를 보여준다
                const stockEvents = open ? allEvents.filter((x) => x.stockCode === e.stockCode) : [];
                const journey = stockEvents;
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
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedCode(e.stockCode);
                        setSelectedMarket(null);
                        setOpenKey(open ? null : rowKey);
                      }}
                      className={`w-full flex items-center flex-wrap gap-x-3 gap-y-1 px-4 py-3 text-left rounded-xl hover:bg-white/[0.03] transition-colors ${
                        e.stockCode === selectedCode ? "bg-emerald-900/30" : ""
                      }`}
                    >
                      {/* 왼쪽: 시각·유형·종목 */}
                      <div className="flex items-center gap-2 min-w-0 flex-1">
                        <span className={`num text-xs tabular-nums w-16 shrink-0 ${clockClass(e.occurredAt)}`}>
                          {clockOf(e.occurredAt)}
                        </span>
                        <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${meta.dot}`} />
                        <span className={`text-xs font-semibold px-1.5 py-0.5 rounded shrink-0 ${meta.chip}`}>
                          {meta.label}
                        </span>
                        <StockAvatar name={e.stockName} code={code} />
                        <span className="text-sm font-semibold text-zinc-100 truncate">{e.stockName}</span>
                      </div>
                      {/* 오른쪽: 디테일·현재가·등락률 (모바일에선 아래 줄로 래핑) */}
                      <div className="flex items-center gap-3 shrink-0 ml-auto pl-[4.5rem] md:pl-0">
                        <span className="num text-xs text-zinc-300">{detailOf(e)}</span>
                        <span className="num text-xs text-zinc-100 w-20 text-right">
                          {formatPrice(e.currentPrice)}
                        </span>
                        <span className="w-16 text-right">
                          <ProfitText
                            value={e.priceChangeRate / 100}
                            format={formatPct}
                            className="num text-xs"
                          />
                        </span>
                      </div>
                    </button>

                    {open && <StockJourney stockCode={e.stockCode} journey={journey} />}
                  </motion.li>
                );
              })}
            </AnimatePresence>
          </ul>
        )}
      </section>
      <div className={`lg:sticky lg:top-20 ${selectedCode || selectedMarket ? "" : "hidden lg:block"}`}>
        {selectedMarket ? (
          <IndexDetailPanel
            market={selectedMarket}
            at={selectedMarketAt}
            changeRate={allMarketEvents.find((m) => m.market === selectedMarket)?.changeRate ?? null}
          />
        ) : (
          <StockDetailPanel stockCode={selectedCode} defaultTab="minute" date={date} />
        )}
      </div>
      </div>
    </div>
  );
}


/** 실시간 로그는 로그인한 사용자만 본다. 미로그인이면 데이터를 부르지 않고 안내만 띄운다 —
 *  호출해봐야 401이고, 불필요한 요청이 화면 폴링 주기마다 반복된다. */
export default function SignalLogPage() {
  const { data: me, isLoading } = useMe();
  if (isLoading) return null;
  if (!me?.authenticated) {
    return (
      <LoginGate
        title="주도주 시그널"
        description="종목·시장 시그널 전이가 발생하는 대로 쌓입니다. 구글 계정으로 로그인하면 바로 볼 수 있습니다."
      />
    );
  }
  return <SignalLogPageInner />;
}
