import { useEffect, useState } from "react";
import { useMe } from "../api/auth";
import LoginGate from "../components/common/LoginGate";
import { AnimatePresence, motion } from "motion/react";
import { useBreakoutRadar } from "../api/queries";
import type { BreakoutRadarItem } from "../types";
import { formatKoreanMoney, formatPct, formatPrice, formatRelative } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import HolidayBanner from "../components/common/HolidayBanner";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import NumWon from "../components/common/NumWon";
import StockAvatar from "../components/common/StockAvatar";
import StockDetailPanel from "../components/common/StockDetailPanel";
import ChangeRateSelector, {
  CHANGE_RATE_OPTIONS,
} from "../components/common/ChangeRateSelector";
import { useArrowStockNav } from "../lib/useArrowStockNav";

const MIN_CHANGE_RATE_KEY = "breakoutRadar.minChangeRate";

/** 키움 마스터 코드 — "009150_AL" 같이 거래소 접미사가 붙으면 앞쪽 6자리만. */
function shortCode(stockCode: string): string {
  const idx = stockCode.indexOf("_");
  return idx > 0 ? stockCode.slice(0, idx) : stockCode;
}

/** 돌파까지 남은 % → 임박도 라벨/색 */
function radarStatus(gap: number): { label: string; cls: string; gap: string } {
  if (gap <= 0)
    return { label: "돌파", cls: "bg-emerald-500/15 text-emerald-400", gap: "text-emerald-400" };
  if (gap < 2)
    return { label: "임박", cls: "bg-red-500/20 text-red-300", gap: "text-red-300" };
  if (gap < 4)
    return { label: "주시", cls: "bg-yellow-500/15 text-yellow-400", gap: "text-yellow-400" };
  return { label: "관망", cls: "bg-zinc-700/40 text-zinc-400", gap: "text-zinc-300" };
}

function BreakoutRadarPageInner() {
  // 등락률 임계값 — 새로고침해도 유지(라디오 풀은 주도주와 별개 키), 기본 7%.
  const [minChangeRate, setMinChangeRate] = useState(() => {
    const raw = localStorage.getItem(MIN_CHANGE_RATE_KEY);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : 7;
  });
  const setRate = (r: number) => {
    setMinChangeRate(r);
    localStorage.setItem(MIN_CHANGE_RATE_KEY, String(r));
  };

  const radarQ = useBreakoutRadar(minChangeRate);
  const data = radarQ.data;
  const stocks = data?.stocks ?? [];

  // 우측 차트에 띄울 선택 종목 — 첫 로드 시 1위 자동 선택
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  useEffect(() => {
    if (selectedCode === null && stocks.length > 0) setSelectedCode(stocks[0].stockCode);
  }, [stocks, selectedCode]);

  // ↑/↓ 방향키로 선택 종목 이동
  useArrowStockNav(stocks.map((s) => s.stockCode), selectedCode, setSelectedCode);

  return (
    <div className="space-y-4">
      <HolidayBanner />
      <div className="flex items-baseline justify-between flex-wrap gap-x-3 gap-y-1">
        <div>
          <h2 className="text-xl font-bold flex items-center gap-2">
            저항 · 지지
            <span
              className={`inline-block w-1.5 h-1.5 rounded-full bg-emerald-500 ${
                radarQ.isFetching ? "animate-ping" : "animate-pulse"
              }`}
            />
          </h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            주도주 후보의 당일 고가(저항선)·저가(지지선) 근접도 · 저항 근접 순 · 5초 자동 갱신
          </p>
        </div>
        <div className="text-xs text-zinc-500 flex items-center gap-2">
          {data?.queriedAt && <span>조회 {formatRelative(data.queriedAt)}</span>}
          {typeof data?.totalCount === "number" && (
            <span className="text-zinc-300 font-medium">{data.totalCount}건</span>
          )}
        </div>
      </div>

      {/* 등락률 필터는 목록 컬럼(50%) 폭에 맞춰 우측 정렬 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="flex justify-end pb-2 border-b border-white/[0.08]">
          <ChangeRateSelector value={minChangeRate} onChange={setRate} />
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
      <section>
        {radarQ.isLoading ? (
          <div className="p-6 space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : stocks.length === 0 ? (
          <EmptyState message="후보 종목이 없습니다" />
        ) : (
          <>
          <div className="hidden md:block overflow-x-auto">
            <table className="w-full text-xs border-separate border-spacing-y-1">
              <thead className="text-zinc-500 text-xs">
                <tr>
                  <th className="px-4 py-2.5 text-left">종목</th>
                  <th className="px-4 py-2.5 text-right">저항선</th>
                  <th className="px-4 py-2.5 text-right">저항까지</th>
                  <th className="px-4 py-2.5 text-right">지지선</th>
                  <th className="px-4 py-2.5 text-right">지지까지</th>
                  <th className="px-4 py-2.5 text-right">상태</th>
                  <th className="px-4 py-2.5 text-right">거래대금</th>
                  <th className="px-4 py-2.5 text-right">현재가</th>
                  <th className="px-4 py-2.5 text-right">등락률</th>
                </tr>
              </thead>
              <tbody>
                <AnimatePresence mode="popLayout">
                  {stocks.map((s) => (
                    <RadarRow
                      key={s.stockCode}
                      s={s}
                      selected={s.stockCode === selectedCode}
                      onSelect={setSelectedCode}
                    />
                  ))}
                </AnimatePresence>
              </tbody>
            </table>
          </div>
          <div className="md:hidden space-y-1">
            {stocks.map((s) => (
              <RadarCard
                key={s.stockCode}
                s={s}
                selected={s.stockCode === selectedCode}
                onSelect={setSelectedCode}
              />
            ))}
          </div>
          </>
        )}
      </section>
      <div className={`lg:sticky lg:top-20 ${selectedCode ? "" : "hidden lg:block"}`}>
        <StockDetailPanel stockCode={selectedCode} defaultTab="minute" />
      </div>
      </div>
    </div>
  );
}

function RadarRow({
  s,
  selected,
  onSelect,
}: {
  s: BreakoutRadarItem;
  selected: boolean;
  onSelect: (code: string) => void;
}) {
  const code = shortCode(s.stockCode);
  const st = radarStatus(s.gapRate);
  const gapWon = s.dayHigh - s.currentPrice;
  const supportWon = s.dayLow === null ? 0 : s.currentPrice - s.dayLow;
  const trough = s.troughAt ? new Date(s.troughAt) : null;
  // 키움 분봉 cntr_tm이 HTS보다 1분 이르게 라벨링됨 — HTS 기준 +1분 보정
  const peak = new Date(s.peakAt);
  peak.setMinutes(peak.getMinutes() + 1);
  const peakTime = peak.toTimeString().slice(0, 5);
  const troughTime = trough
    ? `${String(trough.getHours()).padStart(2, "0")}:${String(trough.getMinutes()).padStart(2, "0")}`
    : "";
  return (
    <motion.tr
      layout
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{
        layout: { type: "spring", stiffness: 600, damping: 42 },
        opacity: { duration: 0.2 },
      }}
      data-stock-code={s.stockCode}
      onClick={() => onSelect(s.stockCode)}
      className={`transition-colors cursor-pointer hover:[&>td]:bg-white/[0.03] [&>td:first-child]:rounded-l-xl [&>td:last-child]:rounded-r-xl ${
        selected ? "[&>td]:bg-emerald-900/40" : ""
      }`}
    >
      <td className="px-4 py-3.5">
        <div className="flex items-center gap-3">
          <StockAvatar name={s.stockName} code={code} />
          <div className="min-w-0">
            <div className="font-semibold text-zinc-100">{s.stockName}</div>
            <div className="text-xs text-zinc-500 num mt-0.5">{code}</div>
          </div>
        </div>
      </td>
      <td className="px-4 py-3.5 text-right">
        <div className="num text-zinc-300">{formatPrice(s.dayHigh)}</div>
        <div className="num text-xs text-zinc-500">{peak.getDate()}일 {peakTime} 형성</div>
      </td>
      <td className={`px-4 py-3.5 text-right num font-semibold ${st.gap}`}>
        {s.gapRate <= 0 ? "돌파" : `${formatPrice(gapWon)}원 (${s.gapRate.toFixed(2)}%)`}
      </td>
      <td className="px-4 py-3.5 text-right">
        {s.dayLow === null ? (
          <span className="text-zinc-600">—</span>
        ) : (
          <>
            <div className="num text-zinc-300">{formatPrice(s.dayLow)}</div>
            <div className="num text-xs text-zinc-500">
              {trough && `${trough.getDate()}일 ${troughTime} 형성`}
            </div>
          </>
        )}
      </td>
      <td className="px-4 py-3.5 text-right num font-semibold text-zinc-400">
        {s.supportGapRate === null
          ? "—"
          : s.supportGapRate <= 0
            ? "이탈"
            : `${formatPrice(supportWon)}원 (${s.supportGapRate.toFixed(2)}%)`}
      </td>
      <td className="px-4 py-3.5 text-right">
        <motion.span
          key={st.label}
          initial={{ scale: 1.25 }}
          animate={{ scale: 1 }}
          transition={{ type: "spring", stiffness: 500, damping: 18 }}
          className={`inline-block text-xs font-medium px-1.5 py-0.5 rounded ${st.cls}`}
        >
          {st.label}
        </motion.span>
      </td>
      <td className="px-4 py-3.5 text-right num text-zinc-400">
        {formatKoreanMoney(s.tradingValue)}
      </td>
      <td className="px-4 py-3.5 text-right num font-medium text-zinc-100">
        <NumWon value={s.currentPrice} />
      </td>
      <td className="px-4 py-3.5 text-right">
        <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num font-medium" />
      </td>
    </motion.tr>
  );
}

/** 모바일 카드 — 표 컬럼을 압축 (저항선·지지선·상태 우선). */
function RadarCard({
  s,
  selected,
  onSelect,
}: {
  s: BreakoutRadarItem;
  selected: boolean;
  onSelect: (code: string) => void;
}) {
  const code = shortCode(s.stockCode);
  const st = radarStatus(s.gapRate);
  const gapWon = s.dayHigh - s.currentPrice;
  const supportWon = s.dayLow === null ? 0 : s.currentPrice - s.dayLow;
  const trough = s.troughAt ? new Date(s.troughAt) : null;
  const peak = new Date(s.peakAt);
  peak.setMinutes(peak.getMinutes() + 1);
  const peakTime = peak.toTimeString().slice(0, 5);
  const troughTime = trough
    ? `${String(trough.getHours()).padStart(2, "0")}:${String(trough.getMinutes()).padStart(2, "0")}`
    : "";
  return (
    <div
      data-stock-code={s.stockCode}
      onClick={() => onSelect(s.stockCode)}
      className={`rounded-xl px-4 py-3.5 flex flex-col gap-1.5 cursor-pointer ${
        selected ? "bg-emerald-900/40" : ""
      }`}
    >
      {/* 1행: 종목 · 상태 */}
      <div className="flex items-center gap-2">
        <StockAvatar name={s.stockName} code={code} size={26} />
        <span className="font-semibold text-zinc-100 truncate flex-1 min-w-0">{s.stockName}</span>
        <span className={`text-xs font-medium px-1.5 py-0.5 rounded shrink-0 ${st.cls}`}>{st.label}</span>
      </div>
      {/* 2행: 저항선 · 저항까지 */}
      <div className="flex items-baseline justify-between gap-2 pl-9">
        <span className="text-xs text-zinc-500 num">
          저항 {formatPrice(s.dayHigh)} · {peak.getDate()}일 {peakTime}
        </span>
        <span className={`num text-sm font-semibold ${st.gap}`}>
          {s.gapRate <= 0 ? "돌파" : `${formatPrice(gapWon)}원 (${s.gapRate.toFixed(2)}%)`}
        </span>
      </div>
      {/* 3행: 지지선 · 지지까지 */}
      {s.dayLow !== null && (
        <div className="flex items-baseline justify-between gap-2 pl-9">
          <span className="text-xs text-zinc-500 num">
            지지 {formatPrice(s.dayLow)}
            {trough && ` · ${trough.getDate()}일 ${troughTime}`}
          </span>
          <span className="num text-sm font-semibold text-zinc-400">
            {s.supportGapRate === null
              ? "—"
              : s.supportGapRate <= 0
                ? "이탈"
                : `${formatPrice(supportWon)}원 (${s.supportGapRate.toFixed(2)}%)`}
          </span>
        </div>
      )}
      {/* 4행: 코드·거래대금 · 현재가·등락률 */}
      <div className="flex items-baseline justify-between gap-2 pl-9">
        <span className="text-xs text-zinc-500 num truncate">
          {code} · {formatKoreanMoney(s.tradingValue)}
        </span>
        <span className="flex items-baseline gap-2 shrink-0">
          <NumWon value={s.currentPrice} className="num text-sm font-medium text-zinc-100" />
          <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num text-xs" />
        </span>
      </div>
    </div>
  );
}


/** 로그인한 사용자만 본다. 미로그인이면 데이터를 부르지 않는다 —
 *  호출해봐야 401이고, 화면 폴링 주기마다 반복된다. */
export default function BreakoutRadarPage() {
  const { data: me, isLoading } = useMe();
  if (isLoading) return null;
  if (!me?.authenticated) {
    return <LoginGate title="저항 · 지지" description="주도주 후보가 당일 고가(저항선)와 저가(지지선)에 얼마나 가까운지 보여줍니다. 구글 계정으로 로그인하면 바로 볼 수 있습니다." />;
  }
  return <BreakoutRadarPageInner />;
}
