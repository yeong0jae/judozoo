import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useSignalEvents } from "../api/queries";
import type { SignalEventItem, SignalEventType } from "../types";
import { formatKoreanMoney, formatPct, formatPrice } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import StockAvatar from "../components/common/StockAvatar";
import StockDetailPanel from "../components/common/StockDetailPanel";
import DateNavigator, { todayStr } from "../components/common/DateNavigator";
import { buildSignalPrompt } from "../lib/signalPrompt";

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
  BREAKOUT: { label: "돌파", chip: "bg-emerald-500/15 text-emerald-400", dot: "bg-emerald-400" },
  BREAKOUT_IMMINENT: { label: "임박", chip: "bg-amber-500/20 text-amber-300", dot: "bg-amber-300" },
  VOLUME_SPIKE: { label: "스파이크", chip: "bg-rose-500/15 text-rose-300", dot: "bg-rose-400" },
};

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
  if (e.gapRate == null) return e.eventType === "BREAKOUT" ? "전고 돌파" : "";
  const line = Math.round(e.currentPrice * (1 + e.gapRate / 100));
  if (e.eventType === "BREAKOUT") return `${formatPrice(line)}원 돌파`;
  return `${formatPrice(line)}원 돌파까지 ${formatPrice(line - e.currentPrice)}원 (${e.gapRate.toFixed(2)}%) 남음`;
}

export default function SignalLogPage() {
  const [date, setDate] = useState(todayStr());
  const eventsQ = useSignalEvents(date);
  const data = eventsQ.data;
  const events = data?.events ?? [];
  const [openKey, setOpenKey] = useState<string | null>(null);

  // 우측 차트에 띄울 선택 종목 — 첫 로드 시 최신 이벤트 종목 자동 선택
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  useEffect(() => {
    if (selectedCode === null && events.length > 0) setSelectedCode(events[0].stockCode);
  }, [events, selectedCode]);

  // LLM 분석용 프롬프트 복사 — 정제 데이터를 클립보드로.
  // Clipboard API는 HTTPS/localhost에서만 동작하므로 HTTP 배포본을 위해 execCommand로 폴백한다.
  const [copied, setCopied] = useState(false);
  const copyPrompt = async () => {
    const text = buildSignalPrompt(date, events);
    let ok = false;
    if (navigator.clipboard && window.isSecureContext) {
      try {
        await navigator.clipboard.writeText(text);
        ok = true;
      } catch {
        ok = false;
      }
    }
    if (!ok) {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      try {
        ok = document.execCommand("copy");
      } catch {
        ok = false;
      }
      document.body.removeChild(ta);
    }
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } else {
      alert("복사에 실패했어요. 브라우저 권한을 확인해 주세요.");
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
        <div>
          <h2 className="text-xl font-bold flex items-center gap-2">
            시그널 로그
            <span
              className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
                eventsQ.isFetching ? "animate-ping" : "animate-pulse"
              }`}
            />
          </h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            선택 날짜의 돌파·임박·스파이크 전이 · 행을 누르면 그 종목의 여정
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={copyPrompt}
            disabled={events.length === 0}
            className="text-xs px-2.5 py-1 rounded-md bg-white/[0.06] text-zinc-300 hover:bg-white/[0.1] disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            {copied ? "복사됨" : "📋 분석 프롬프트 복사"}
          </button>
          {typeof data?.totalCount === "number" && (
            <span className="text-xs text-zinc-300 font-medium">{data.totalCount}건</span>
          )}
          <DateNavigator
            date={date}
            onChange={(d) => {
              setDate(d);
              setSelectedCode(null);
            }}
          />
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 items-start">
      <section className="bg-zinc-900 border border-white/[0.04] rounded-2xl overflow-hidden">
        {eventsQ.isLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : events.length === 0 ? (
          <EmptyState message={`${date} 시그널이 없습니다`} />
        ) : (
          <ul className="divide-y divide-white/[0.04]">
            <AnimatePresence initial={false}>
              {events.map((e, i) => {
                const code = shortCode(e.stockCode);
                const meta = EVENT_META[e.eventType];
                const rowKey = `${e.stockCode}-${e.eventType}-${e.occurredAt}-${i}`;
                const open = openKey === rowKey;
                // 같은 종목 이벤트 모음(피드·여정 모두 최신순). 누적 거래대금은 최신 스냅샷
                const stockEvents = open ? events.filter((x) => x.stockCode === e.stockCode) : [];
                const journey = stockEvents;
                return (
                  <motion.li
                    key={rowKey}
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
                        setOpenKey(open ? null : rowKey);
                      }}
                      className={`w-full flex items-center flex-wrap gap-x-3 gap-y-1 px-4 py-3 text-left hover:bg-white/[0.03] transition-colors ${
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

                    {open && (
                      <div className="px-4 pb-3 pt-1 bg-white/[0.02]">
                        <div className="text-xs text-zinc-500 mb-2">
                          {e.stockName} 여정 · 누적 거래대금 {formatKoreanMoney(stockEvents[0].tradingValue)}
                        </div>
                        <ol className="space-y-1.5 border-l border-white/10 ml-2 pl-4">
                          {journey.map((j, k) => {
                            const jm = EVENT_META[j.eventType];
                            return (
                              <li key={`${j.eventType}-${j.occurredAt}-${k}`} className="flex items-center gap-2 text-sm">
                                <span className={`num text-xs tabular-nums w-16 ${clockClass(j.occurredAt)}`}>
                                  {clockOf(j.occurredAt)}
                                </span>
                                <span className={`text-xs font-semibold px-1.5 py-0.5 rounded ${jm.chip}`}>
                                  {jm.label}
                                </span>
                                <span className="num text-xs text-zinc-300">{detailOf(j)}</span>
                                <span className="num text-xs text-zinc-500 ml-auto">
                                  {formatPrice(j.currentPrice)}
                                </span>
                              </li>
                            );
                          })}
                        </ol>
                      </div>
                    )}
                  </motion.li>
                );
              })}
            </AnimatePresence>
          </ul>
        )}
      </section>
      <div className={`lg:sticky lg:top-20 ${selectedCode ? "" : "hidden lg:block"}`}>
        <StockDetailPanel stockCode={selectedCode} defaultTab="minute" />
      </div>
      </div>
    </div>
  );
}
