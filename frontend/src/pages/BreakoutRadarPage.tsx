import { useEffect, useState } from "react";
import { useMe } from "../api/auth";
import LoginGate from "../components/common/LoginGate";
import { AnimatePresence, motion } from "motion/react";
import { LIVE_REFRESH_MS, useBreakoutRadar } from "../api/queries";
import type { BreakoutRadarItem } from "../types";
import { formatKoreanMoney, formatPct, formatPrice } from "../lib/format";
import ProfitText from "../components/common/ProfitText";
import PageHeader from "../components/layout/PageHeader";
import Skeleton from "../components/common/Skeleton";
import EmptyState from "../components/common/EmptyState";
import NumWon from "../components/common/NumWon";
import GoogleLoginButton from "../components/common/GoogleLoginButton";
import StockDetailPanel from "../components/common/StockDetailPanel";
import { useArrowStockNav } from "../lib/useArrowStockNav";
import ListDetail, { useMobileDetail } from "../components/layout/ListDetail";

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
  { key: "support", label: "눌림" },
  { key: "resistance", label: "돌파" },
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
          className={`px-3.5 py-[5px] rounded-lg transition-colors ${
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
  const [mode, setMode] = useState<RadarMode>(() =>
    localStorage.getItem(MODE_KEY) === "support" ? "support" : "resistance",
  );
  const setRadarMode = (m: RadarMode) => {
    localStorage.setItem(MODE_KEY, m);
    setMode(m);
  };

  const radarQ = useBreakoutRadar(mode);
  const data = radarQ.data;
  // 지금 보는 선에서 NEAR% 이내인 것만 오고, 가까운 순으로 정렬돼 있다 — 거르고 세우는 일은
  // 서버가 한다. 화면에서 또 거르면 `totalCount`와 모수가 달라져 "N개 중 M개"가 어긋난다.
  const stocks = data?.stocks ?? [];
  const mobile = useMobileDetail();

  // 우측 상세에 띄울 선택 종목 — 첫 로드 시 1위 자동 선택
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  useEffect(() => {
    if (!authenticated) return;
    if (selectedCode === null && stocks.length > 0) setSelectedCode(stocks[0].stockCode);
  }, [authenticated, stocks, selectedCode]);

  // ↑/↓ 방향키로 선택 종목 이동
  useArrowStockNav(authenticated ? stocks.map((s) => s.stockCode) : [], selectedCode, setSelectedCode);

  // 미로그인이면 없다 — 눌러도 열 상세가 없어 클릭을 막는다
  const onSelect = authenticated
    ? (code: string) => {
        setSelectedCode(code);
        mobile.show();
      }
    : undefined;
  const lineName = mode === "support" ? "눌림선" : "돌파선";
  const gated = !authenticated && (data?.totalCount ?? 0) > (data?.stocks.length ?? 0);

  const list = (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-3 lg:px-3.5">
        <PageHeader
          title="눌림·돌파"
          // 근접 범위로 거른 뒤의 전체 수 — 미로그인은 그중 다섯 줄만 받는다
          count={data?.totalCount}
          fetchedAt={radarQ.dataUpdatedAt}
          refreshMs={LIVE_REFRESH_MS}
          loading={radarQ.isFetching}
        />
        <div className="flex flex-wrap items-center justify-between gap-2">
          <ModeToggle value={mode} onChange={setRadarMode} />
          <span className="text-xs text-zinc-500">
            {lineName} {NEAR}% 이내 · 가까운 순
          </span>
        </div>
      </div>

      {radarQ.isLoading ? (
        <div className="space-y-3 p-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : stocks.length === 0 ? (
        <EmptyState message={`${lineName} ${NEAR}% 안에 든 종목이 없습니다`} />
      ) : (
        <>
          <div className="hidden lg:block">
            <div className={`grid ${RADAR_COLS} gap-x-3 px-3.5 pb-1.5 text-[11px] text-zinc-500`}>
              <span>종목</span>
              <span className="text-right">{mode === "resistance" ? "돌파선" : "눌림선"}</span>
              <span className="text-right">{mode === "resistance" ? "돌파까지" : "눌림부터"}</span>
              <span className="text-right">현재가 · 거래대금</span>
              <span className="text-right">등락률</span>
            </div>
            <AnimatePresence mode="popLayout">
              {stocks.map((s) => (
                <RadarRow
                  key={s.stockCode}
                  mode={mode}
                  s={s}
                  selected={s.stockCode === selectedCode}
                  onSelect={onSelect}
                />
              ))}
            </AnimatePresence>
            {gated && <PreviewGateBody shown={stocks.length} total={data?.totalCount ?? 0} widths={[128, 168, 104]} />}
          </div>
          <div className="lg:hidden">
            {stocks.map((s) => (
              <RadarCard
                key={s.stockCode}
                mode={mode}
                s={s}
                selected={s.stockCode === selectedCode}
                onSelect={onSelect}
              />
            ))}
            {gated && <PreviewGateBody shown={stocks.length} total={data?.totalCount ?? 0} widths={[112, 140, 96]} />}
          </div>
        </>
      )}
    </div>
  );

  const detail = authenticated ? (
    <StockDetailPanel stockCode={selectedCode} onBack={mobile.hide} />
  ) : (
    <LoginGate
      title="종목 상세"
      description="주도주 조건·분봉·일봉·투자자 수급을 종목별로 봅니다. 로그인 후 확인할 수 있습니다."
    />
  );

  return <ListDetail list={list} detail={detail} detailOpen={mobile.open} />;
}

/** 데스크톱 줄의 열 — 종목 · 선 · 남은 거리 · 현재가와 거래대금 · 등락률. */
const RADAR_COLS = "grid-cols-[minmax(0,1fr)_6.5rem_5rem_6.5rem_4.5rem]";

/** 선까지 남은 거리 — 넘었으면 "돌파"/"이탈", 아니면 원과 %. */
function radarGap(mode: RadarMode, s: BreakoutRadarItem) {
  if (mode === "resistance") {
    return {
      crossed: s.gapRate <= 0 ? "돌파" : null,
      pct: s.gapRate,
      won: s.peakPrice - s.currentPrice,
      cls: resistanceCls(s.gapRate),
    };
  }
  return {
    crossed: s.supportGapRate !== null && s.supportGapRate <= 0 ? "이탈" : null,
    pct: s.supportGapRate,
    won: s.troughPrice === null ? null : s.currentPrice - s.troughPrice,
    cls: supportCls(s.supportGapRate),
  };
}

/** 선이 만들어진 시각 — "23일 10:42". 키움 분봉 cntr_tm이 HTS보다 1분 이르게 라벨링돼 고점은 +1분 보정. */
function formedAt(mode: RadarMode, s: BreakoutRadarItem): { price: number | null; at: string | null } {
  const hm = (d: Date) => `${d.getDate()}일 ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  if (mode === "resistance") {
    const peak = new Date(s.peakAt);
    peak.setMinutes(peak.getMinutes() + 1);
    return { price: s.peakPrice, at: hm(peak) };
  }
  return { price: s.troughPrice, at: s.troughAt ? hm(new Date(s.troughAt)) : null };
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
  const gap = radarGap(mode, s);
  const line = formedAt(mode, s);
  return (
    <motion.button
      type="button"
      layout
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{
        layout: { type: "spring", stiffness: 600, damping: 42 },
        opacity: { duration: 0.2 },
      }}
      data-stock-code={s.stockCode}
      disabled={!onSelect}
      onClick={onSelect ? () => onSelect(s.stockCode) : undefined}
      className={`grid w-full ${RADAR_COLS} items-center gap-x-3 rounded-xl px-3.5 py-2.5 text-left transition-colors ${
        onSelect ? "hover:bg-zinc-850" : "cursor-default"
      } ${selected ? "bg-selected hover:bg-selected" : ""}`}
    >
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="truncate text-[13.5px] text-zinc-100">{s.stockName}</span>
        <span className="num text-[11px] text-zinc-500">{shortCode(s.stockCode)}</span>
      </span>
      <span className="flex flex-col items-end gap-0.5">
        {line.price === null ? (
          <span className="text-zinc-600">—</span>
        ) : (
          <>
            <span className="num text-[13px] text-zinc-300">{formatPrice(line.price)}</span>
            {line.at && <span className="num text-[11px] text-zinc-500">{line.at} 형성</span>}
          </>
        )}
      </span>
      <span className={`flex flex-col items-end gap-0.5 ${gap.cls}`}>
        {gap.crossed ? (
          <span className="text-[13px]">{gap.crossed}</span>
        ) : gap.pct === null || gap.won === null ? (
          <span className="text-zinc-600">—</span>
        ) : (
          <>
            <span className="num text-[13px]">{gap.pct.toFixed(2)}%</span>
            <span className="num text-[11px] text-zinc-500">{formatPrice(gap.won)}원</span>
          </>
        )}
      </span>
      <span className="flex flex-col items-end gap-0.5">
        <NumWon value={s.currentPrice} className="num text-[13.5px] text-zinc-100" />
        <span className="num text-[11px] text-zinc-500">{formatKoreanMoney(s.tradingValue)}</span>
      </span>
      <span className="text-right">
        <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num text-xs" />
      </span>
    </motion.button>
  );
}

/** 모바일 카드 — 윗줄 종목·남은 거리, 아랫줄 선·형성 시각과 현재가·등락률. */
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
  const gap = radarGap(mode, s);
  const line = formedAt(mode, s);
  return (
    <button
      type="button"
      data-stock-code={s.stockCode}
      disabled={!onSelect}
      onClick={onSelect ? () => onSelect(s.stockCode) : undefined}
      className={`flex w-full flex-col gap-1 rounded-xl px-2.5 py-2.5 text-left ${
        onSelect ? "" : "cursor-default"
      } ${selected ? "bg-selected" : ""}`}
    >
      <span className="flex w-full items-baseline justify-between gap-2.5">
        <span className="min-w-0 truncate text-[13.5px] text-zinc-100">{s.stockName}</span>
        <span className={`num shrink-0 text-[13px] ${gap.cls}`}>
          {gap.crossed ??
            (gap.pct === null || gap.won === null ? "—" : `${formatPrice(gap.won)}원 (${gap.pct.toFixed(2)}%)`)}
        </span>
      </span>
      <span className="flex w-full items-baseline justify-between gap-2.5">
        <span className="num truncate text-[11px] text-zinc-500">
          {line.price !== null && `${mode === "resistance" ? "돌파선" : "눌림선"} ${formatPrice(line.price)}`}
          {line.at && ` · ${line.at}`}
        </span>
        <span className="flex shrink-0 items-baseline gap-2">
          <NumWon value={s.currentPrice} className="num text-xs text-zinc-300" />
          <ProfitText value={s.priceChangeRate / 100} format={formatPct} className="num text-xs" />
        </span>
      </span>
    </button>
  );
}


/** 로그인한 사용자만 본다. 미로그인이면 데이터를 부르지 않는다 —
 *  호출해봐야 401이고, 화면 폴링 주기마다 반복된다. */
export default function BreakoutRadarPage() {
  const { data: me, isLoading } = useMe();
  if (isLoading) return null;
  return <BreakoutRadarPageInner authenticated={!!me?.authenticated} />;
}
