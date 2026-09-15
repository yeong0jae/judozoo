import { useEffect, useMemo, useState } from "react";
import { useMe } from "../api/auth";
import LoginGate from "../components/common/LoginGate";
import { AnimatePresence, motion } from "motion/react";
import { useBreakoutRadar } from "../api/queries";
import type { BreakoutRadarItem } from "../types";
import { formatKoreanMoney, formatPct, formatPrice, formatRelative } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import PageHeader from "../components/layout/PageHeader";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import NumWon from "../components/common/NumWon";
import StockAvatar from "../components/common/StockAvatar";
import GoogleLoginButton from "../components/common/GoogleLoginButton";
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



/** 근접 강조 — 3% 이내면 색으로 띄운다.
 *  저항은 따뜻한 색(위), 지지는 차가운 색(아래). 등락률의 red-600/blue-600과는
 *  명도가 달라 한 행에 같이 있어도 구분된다. */
const NEAR = 3;
const MODE_KEY = "radar.mode";

type RadarMode = "resistance" | "support";
const MODES: { key: RadarMode; label: string }[] = [
  { key: "support", label: "지지" },
  { key: "resistance", label: "저항" },
];

/** 보는 모드를 고른다. 정렬 기준도 같이 바뀐다 — 돌파매매와 눌림매매는 동시에 보는 게 아니다. */
function ModeToggle({ value, onChange }: { value: RadarMode; onChange: (v: RadarMode) => void }) {
  return (
    <div className="flex rounded-xl bg-zinc-800 p-0.5 text-xs shrink-0">
      {MODES.map((m) => (
        <button
          key={m.key}
          type="button"
          onClick={() => onChange(m.key)}
          className={`px-4 py-1.5 rounded-lg transition-colors ${
            value === m.key
              ? "bg-elevated text-zinc-100 font-medium"
              : "text-zinc-500 hover:text-zinc-300"
          }`}
        >
          {m.label}
        </button>
      ))}
    </div>
  );
}
const resistanceCls = (gap: number) =>
  gap <= NEAR ? "text-orange-400" : "text-zinc-300";
const supportCls = (gap: number | null) =>
  gap !== null && gap <= NEAR ? "text-sky-400" : "text-zinc-400";

/**
 * 미리보기 끝 — 아래로 더 있다는 걸 보이고 로그인으로 잇는다.
 *
 * 막대는 가짜다. 서버가 이미 잘라서 채울 내용이 없다(진짜 행을 흐리게 깔면
 * 그 데이터가 브라우저까지 내려와야 하므로 차단이 아니라 가리기가 된다).
 * 목록이 표(데스크톱)와 카드(모바일) 두 벌이라 껍데기도 두 벌이다.
 */
function PreviewGateBody({ shown, total, widths }: { shown: number; total: number; widths: number[] }) {
  return (
    <div className="relative">
      {/* 아래로 갈수록 지워진다 — 줄마다 농도를 주면 계단이 생겨 "이어진다"가 덜 읽힌다 */}
      <div
        aria-hidden
        className="select-none opacity-60"
        style={{
          maskImage: "linear-gradient(#000, transparent)",
          WebkitMaskImage: "linear-gradient(#000, transparent)",
        }}
      >
        {widths.map((w, i) => (
          <div key={i} className="flex items-center gap-3 px-4 py-3.5">
            <span className="h-7 w-7 shrink-0 rounded-full bg-zinc-700" />
            <span className="h-3.5 rounded bg-zinc-700" style={{ width: w }} />
            <span className="ml-auto h-3 w-16 rounded bg-zinc-700" />
          </div>
        ))}
      </div>
      <div className="absolute inset-0 flex flex-col items-center justify-center gap-2.5">
        <p className="text-xs text-zinc-400">
          오늘 <span className="num">{total}</span>개 중 <span className="num">{shown}</span>개를 보고 있습니다
        </p>
        <GoogleLoginButton />
      </div>
    </div>
  );
}

function BreakoutRadarPageInner({ authenticated }: { authenticated: boolean }) {
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

  const [mode, setMode] = useState<RadarMode>(() =>
    localStorage.getItem(MODE_KEY) === "support" ? "support" : "resistance",
  );
  const setRadarMode = (m: RadarMode) => {
    localStorage.setItem(MODE_KEY, m);
    setMode(m);
  };

  // 미로그인은 하한 없이 전체로 받는다. 7%를 걸면 지지 모드가 통째로 빈다 —
  // 지지선에 닿는 건 대개 떨어지는 종목이라서다. 선택기도 감추므로 저장값을 쓰지 않는다.
  const radarQ = useBreakoutRadar(authenticated ? minChangeRate : CHANGE_RATE_OPTIONS[0], mode);
  const data = radarQ.data;
  // 백엔드는 저항 근접 순으로 준다. 지지 모드면 여기서 다시 세운다 —
  // 데이터가 이미 다 와 있어서 추가 요청이 필요 없다.
  const stocks = useMemo(() => {
    const list = data?.stocks ?? [];
    if (mode === "resistance") return list;
    return [...list].sort(
      (a, b) => (a.supportGapRate ?? Infinity) - (b.supportGapRate ?? Infinity),
    );
  }, [data, mode]);

  // 우측 차트에 띄울 선택 종목 — 첫 로드 시 1위 자동 선택
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  useEffect(() => {
    if (!authenticated) return;
    if (selectedCode === null && stocks.length > 0) setSelectedCode(stocks[0].stockCode);
  }, [authenticated, stocks, selectedCode]);

  // ↑/↓ 방향키로 선택 종목 이동
  useArrowStockNav(authenticated ? stocks.map((s) => s.stockCode) : [], selectedCode, setSelectedCode);

  return (
    <div className="space-y-4">
      <PageHeader
        title="지지·저항"
        count={data?.totalCount}
        queriedAt={data?.queriedAt ? formatRelative(data.queriedAt) : undefined}
        loading={radarQ.isFetching}
      />

      {/* 등락률 필터는 목록 컬럼(50%) 폭에 맞춰 우측 정렬 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* 폰에서는 한 줄에 못 들어간다 — 선택기가 아랫줄로 내려가게 접는다 */}
        <div className="flex flex-wrap items-center justify-between gap-2 pb-2 border-b border-zinc-800">
          <ModeToggle value={mode} onChange={setRadarMode} />
          {authenticated ? (
            <ChangeRateSelector value={minChangeRate} onChange={setRate} />
          ) : (
            <span className="text-[11.5px] text-zinc-600">등락률 선택은 로그인 뒤</span>
          )}
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
                  <th className="px-4 py-2.5 text-right">{mode === "resistance" ? "저항선" : "지지선"}</th>
                  <th className="px-4 py-2.5 text-right">{mode === "resistance" ? "저항까지" : "지지부터"}</th>
                  <th className="px-4 py-2.5 text-right">거래대금</th>
                  <th className="px-4 py-2.5 text-right">등락률</th>
                </tr>
              </thead>
              <tbody>
                <AnimatePresence mode="popLayout">
                  {stocks.map((s) => (
                    <RadarRow
              mode={mode}
                      key={s.stockCode}
                      s={s}
                      selected={s.stockCode === selectedCode}
                      onSelect={authenticated ? setSelectedCode : undefined}
                    />
                  ))}
                </AnimatePresence>
                {!authenticated && (data?.totalCount ?? 0) > stocks.length && (
                  <tr>
                    <td colSpan={5} className="p-0">
                      <PreviewGateBody
                        shown={stocks.length}
                        total={data?.totalCount ?? 0}
                        widths={[128, 168, 104]}
                      />
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <div className="md:hidden space-y-1">
            {stocks.map((s) => (
              <RadarCard
            mode={mode}
                key={s.stockCode}
                s={s}
                selected={s.stockCode === selectedCode}
                onSelect={authenticated ? setSelectedCode : undefined}
              />
            ))}
            {!authenticated && (data?.totalCount ?? 0) > stocks.length && (
              <PreviewGateBody
                shown={stocks.length}
                total={data?.totalCount ?? 0}
                widths={[112, 140, 96]}
              />
            )}
          </div>
          </>
        )}
      </section>
      <div className={`lg:sticky lg:top-20 ${selectedCode ? "" : "hidden lg:block"}`}>
        {authenticated ? (
          <StockDetailPanel stockCode={selectedCode} defaultTab="minute" />
        ) : (
          <LoginGate
            title="종목 상세"
            description="필터 평가·분봉·일봉·투자자 수급을 종목별로 봅니다. 로그인하면 확인할 수 있습니다."
          />
        )}
      </div>
      </div>
    </div>
  );
}

function RadarRow({
  mode,
  s,
  selected,
  onSelect,
}: {
  mode: RadarMode;
  s: BreakoutRadarItem;
  selected: boolean;
  /** 미로그인이면 없다 — 눌러도 열 상세가 없어 클릭을 막는다 */
  onSelect?: (code: string) => void;
}) {
  const code = shortCode(s.stockCode);
  const gapWon = s.peakPrice - s.currentPrice;
  const supportWon = s.troughPrice === null ? 0 : s.currentPrice - s.troughPrice;
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
      onClick={onSelect ? () => onSelect(s.stockCode) : undefined}
      className={`transition-colors [&>td:first-child]:rounded-l-xl [&>td:last-child]:rounded-r-xl ${
        onSelect ? "cursor-pointer hover:[&>td]:bg-zinc-850" : ""
      } ${selected ? "[&>td]:bg-selected" : ""}`}
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
      {mode === "resistance" ? (
        <>
          <td className="px-4 py-3.5 text-right">
            <div className="num text-zinc-300">{formatPrice(s.peakPrice)}</div>
            <div className="num text-xs text-zinc-500">{peak.getDate()}일 {peakTime} 형성</div>
          </td>
          <td className={`px-4 py-3.5 text-right num font-semibold ${resistanceCls(s.gapRate)}`}>
            {s.gapRate <= 0 ? "돌파" : `${formatPrice(gapWon)}원 (${s.gapRate.toFixed(2)}%)`}
          </td>
        </>
      ) : (
        <>
          <td className="px-4 py-3.5 text-right">
            {s.troughPrice === null ? (
              <span className="text-zinc-600">—</span>
            ) : (
              <>
                <div className="num text-zinc-300">{formatPrice(s.troughPrice)}</div>
                <div className="num text-xs text-zinc-500">
                  {trough && `${trough.getDate()}일 ${troughTime} 형성`}
                </div>
              </>
            )}
          </td>
          <td className={`px-4 py-3.5 text-right num font-semibold ${supportCls(s.supportGapRate)}`}>
            {s.supportGapRate === null
              ? "—"
              : s.supportGapRate <= 0
                ? "이탈"
                : `${formatPrice(supportWon)}원 (${s.supportGapRate.toFixed(2)}%)`}
          </td>
        </>
      )}
      <td className="px-4 py-3.5 text-right num text-zinc-400">
        {formatKoreanMoney(s.tradingValue)}
      </td>
      <td className="px-4 py-3.5 text-right">
        <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num font-medium" />
      </td>
    </motion.tr>
  );
}

/** 모바일 카드 — 표 컬럼을 압축 (저항선·지지선·상태 우선). */
function RadarCard({
  mode,
  s,
  selected,
  onSelect,
}: {
  mode: RadarMode;
  s: BreakoutRadarItem;
  selected: boolean;
  /** 미로그인이면 없다 — 표 행과 같다 */
  onSelect?: (code: string) => void;
}) {
  const code = shortCode(s.stockCode);
  const gapWon = s.peakPrice - s.currentPrice;
  const supportWon = s.troughPrice === null ? 0 : s.currentPrice - s.troughPrice;
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
      onClick={onSelect ? () => onSelect(s.stockCode) : undefined}
      className={`rounded-xl px-4 py-3.5 flex flex-col gap-1.5 ${
        onSelect ? "cursor-pointer" : ""
      } ${selected ? "bg-selected" : ""}`}
    >
      {/* 1행: 종목 */}
      <div className="flex items-center gap-2">
        <StockAvatar name={s.stockName} code={code} size={26} />
        <span className="font-semibold text-zinc-100 truncate flex-1 min-w-0">{s.stockName}</span>
      </div>
      {/* 2행: 모드에 해당하는 선 한 쌍 */}
      {mode === "resistance" ? (
        <div className="flex items-baseline justify-between gap-2 pl-9">
          <span className="text-xs text-zinc-500 num">
            저항 {formatPrice(s.peakPrice)} · {peak.getDate()}일 {peakTime}
          </span>
          <span className={`num text-sm font-semibold ${resistanceCls(s.gapRate)}`}>
            {s.gapRate <= 0 ? "돌파" : `${formatPrice(gapWon)}원 (${s.gapRate.toFixed(2)}%)`}
          </span>
        </div>
      ) : (
        s.troughPrice !== null && (
          <div className="flex items-baseline justify-between gap-2 pl-9">
            <span className="text-xs text-zinc-500 num">
              지지 {formatPrice(s.troughPrice)}
              {trough && ` · ${trough.getDate()}일 ${troughTime}`}
            </span>
            <span className={`num text-sm font-semibold ${supportCls(s.supportGapRate)}`}>
              {s.supportGapRate === null
                ? "—"
                : s.supportGapRate <= 0
                  ? "이탈"
                  : `${formatPrice(supportWon)}원 (${s.supportGapRate.toFixed(2)}%)`}
            </span>
          </div>
        )
      )}
      {/* 3행: 코드·거래대금 · 현재가·등락률 */}
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
  return <BreakoutRadarPageInner authenticated={!!me?.authenticated} />;
}
