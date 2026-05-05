import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  mockActiveCommands,
  mockBalance,
  mockStockPrices,
  mockStockSearch,
  mockSystemStatus,
} from "../mocks/data";
import type {
  ErrorCode,
  StockSearchResult,
  SystemStatus,
} from "../types";
import {
  formatKRW,
  formatPrice,
  formatQty,
  formatRelative,
} from "../lib/format";
import { errorMessage } from "../lib/errorMessages";
import StatusPill from "../components/common/StatusPill";
import ProfitText from "../components/common/ProfitText";
import FlashOnChange from "../components/common/FlashOnChange";
import { useToast } from "../components/toast/Toast";
import { formatPct } from "../lib/format";

interface Advanced {
  buyIntervalMin: number;
  splitSellRatio: number; // %
  midwayProfitPct: number;
  breakevenThresholdPct: number;
  stopLossPct: number; // 음수
}

const DEFAULTS: Advanced = {
  buyIntervalMin: 3,
  splitSellRatio: 20,
  midwayProfitPct: 3,
  breakevenThresholdPct: 2,
  stopLossPct: -2,
};

export default function CommandPage() {
  const toast = useToast();
  const [query, setQuery] = useState("");
  const [selectedStock, setSelectedStock] =
    useState<StockSearchResult | null>(null);
  const [priceRefreshAt, setPriceRefreshAt] = useState(Date.now());
  const [perBuyAmount, setPerBuyAmount] = useState(1_000_000);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [advanced, setAdvanced] = useState<Advanced>(DEFAULTS);
  const [serverError, setServerError] = useState<ErrorCode | null>(null);

  const status = mockSystemStatus;
  const balance = mockBalance;

  const block = deriveBlock(status);
  const price = selectedStock
    ? mockStockPrices[selectedStock.stockCode] ?? null
    : null;
  const currentPrice = price?.currentPrice ?? 0;

  const estimatedQty =
    currentPrice > 0 ? Math.floor(perBuyAmount / currentPrice) : 0;
  const totalReserve = perBuyAmount * 3;
  const totalActualBuyEstimate = currentPrice * estimatedQty;
  const insufficientBalance = totalReserve > balance.availableBalance;
  const belowOneShare = currentPrice > 0 && perBuyAmount < currentPrice;
  const duplicateActive =
    selectedStock !== null &&
    mockActiveCommands.some(
      (c) => c.stockCode === selectedStock.stockCode,
    );

  const advancedDirty = useMemo(
    () =>
      (Object.keys(DEFAULTS) as (keyof Advanced)[]).filter(
        (k) => advanced[k] !== DEFAULTS[k],
      ),
    [advanced],
  );

  const submitDisabled =
    !!block ||
    !selectedStock ||
    insufficientBalance ||
    belowOneShare ||
    perBuyAmount < 10_000;

  const onSubmit = () => {
    setServerError(null);
    if (duplicateActive) {
      setServerError("DUPLICATE_COMMAND");
      return;
    }
    if (!selectedStock) return;
    // mock 성공 처리
    toast.show({
      tone: "success",
      message: `${selectedStock.stockName} 매매가 시작되었습니다`,
      action: {
        label: "모니터링에서 확인 →",
        onClick: () => {
          window.location.assign("/monitoring");
        },
      },
    });
    setQuery("");
    setSelectedStock(null);
    setPerBuyAmount(1_000_000);
    setAdvanced(DEFAULTS);
    setAdvancedOpen(false);
  };

  return (
    <div className="space-y-6">
      {block && <BlockBanner reason={block} />}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <section className="lg:col-span-2 bg-zinc-900 border border-zinc-800 rounded-lg p-6 space-y-6">
          <h2 className="text-lg font-semibold">새 매매 명령</h2>

          <Field
            number={1}
            label="종목"
            error={serverError === "STOCK_NOT_FOUND" || serverError === "DUPLICATE_COMMAND"}
          >
            <StockSearchInput
              query={query}
              setQuery={setQuery}
              selected={selectedStock}
              onSelect={(s) => {
                setSelectedStock(s);
                setQuery("");
                setServerError(null);
              }}
            />
            {selectedStock && (
              <PriceDisplay
                stock={selectedStock}
                currentPrice={currentPrice}
                asOf={price?.asOf}
                onRefresh={() => setPriceRefreshAt(Date.now())}
                refreshKey={priceRefreshAt}
                onClear={() => setSelectedStock(null)}
              />
            )}
            {duplicateActive && (
              <ErrorMsg code="DUPLICATE_COMMAND" />
            )}
            {serverError === "STOCK_NOT_FOUND" && (
              <ErrorMsg code="STOCK_NOT_FOUND" />
            )}
          </Field>

          <Field
            number={2}
            label="1회 매수금액"
            disabled={!selectedStock || !!block}
            error={
              insufficientBalance ||
              belowOneShare ||
              serverError === "PRICE_BELOW_ONE_SHARE" ||
              serverError === "INSUFFICIENT_BALANCE"
            }
          >
            <AmountInput
              value={perBuyAmount}
              onChange={setPerBuyAmount}
              disabled={!selectedStock || !!block}
            />
            {selectedStock && (
              <AmountPreview
                perBuyAmount={perBuyAmount}
                totalReserve={totalReserve}
                estimatedQty={estimatedQty}
                actualBuyEstimate={totalActualBuyEstimate}
                currentPrice={currentPrice}
                availableBalance={balance.availableBalance}
                insufficientBalance={insufficientBalance}
                belowOneShare={belowOneShare}
              />
            )}
          </Field>

          <Field number={3} label="고급 설정">
            <button
              type="button"
              onClick={() => setAdvancedOpen((v) => !v)}
              className="text-sm text-zinc-400 hover:text-zinc-200 flex items-center gap-1"
            >
              <span>{advancedOpen ? "▾" : "▸"}</span>
              <span>
                {advancedDirty.length === 0
                  ? "기본값 사용 중"
                  : `${advancedDirty.length}개 항목 변경됨`}
              </span>
            </button>
            {advancedOpen && (
              <AdvancedSettings
                value={advanced}
                onChange={setAdvanced}
                dirtyKeys={new Set(advancedDirty)}
              />
            )}
          </Field>

          <button
            disabled={submitDisabled}
            onClick={onSubmit}
            className="w-full bg-emerald-700 hover:bg-emerald-600 disabled:bg-zinc-800 disabled:text-zinc-600 text-white px-4 py-3 rounded-md font-medium transition-colors"
          >
            매매 시작
          </button>

          {serverError &&
            !["STOCK_NOT_FOUND", "DUPLICATE_COMMAND"].includes(serverError) && (
              <ErrorMsg code={serverError} />
            )}
        </section>

        <aside className="space-y-6">
          <BalancePanel balance={balance} insufficient={insufficientBalance} />
          <SystemPanel status={status} />
          <ActiveCommandsPreview
            commands={mockActiveCommands}
            highlightStock={selectedStock?.stockCode}
          />
        </aside>
      </div>
    </div>
  );
}

// ============================================================
// Block banner
// ============================================================

function deriveBlock(s: SystemStatus): {
  code: ErrorCode;
  message: string;
  tone: "warn" | "danger";
} | null {
  if (s.tokenStatus !== "OK")
    return {
      code: "INVALID_PARAMETER",
      message: "KIS 토큰 오류 — 명령 차단",
      tone: "danger",
    };
  if (s.isHoliday)
    return {
      code: "HOLIDAY",
      message: errorMessage("HOLIDAY"),
      tone: "warn",
    };
  if (!s.tradingHoursOpen)
    return {
      code: "OUT_OF_TRADING_HOURS",
      message: errorMessage("OUT_OF_TRADING_HOURS"),
      tone: "warn",
    };
  if (s.cutoffPassed)
    return {
      code: "CUTOFF_PASSED",
      message: errorMessage("CUTOFF_PASSED"),
      tone: "warn",
    };
  return null;
}

function BlockBanner({
  reason,
}: {
  reason: { message: string; tone: "warn" | "danger" };
}) {
  const cls =
    reason.tone === "danger"
      ? "bg-rose-950/60 border-rose-800 text-rose-200"
      : "bg-amber-950/60 border-amber-800 text-amber-200";
  return (
    <div className={`border rounded-lg px-4 py-3 text-sm ${cls}`}>
      ⛔ {reason.message}
    </div>
  );
}

// ============================================================
// Form fields
// ============================================================

function Field({
  number,
  label,
  children,
  error,
  disabled,
}: {
  number: number;
  label: string;
  children: React.ReactNode;
  error?: boolean;
  disabled?: boolean;
}) {
  return (
    <div className={disabled ? "opacity-50 pointer-events-none" : ""}>
      <label
        className={`text-sm font-medium mb-2 flex items-center gap-2 ${
          error ? "text-rose-300" : "text-zinc-200"
        }`}
      >
        <span className="w-5 h-5 rounded-full bg-zinc-800 text-xs flex items-center justify-center text-zinc-400">
          {number}
        </span>
        {label}
      </label>
      <div className="space-y-2">{children}</div>
    </div>
  );
}

function StockSearchInput({
  query,
  setQuery,
  selected,
  onSelect,
}: {
  query: string;
  setQuery: (v: string) => void;
  selected: StockSearchResult | null;
  onSelect: (s: StockSearchResult) => void;
}) {
  const [activeIdx, setActiveIdx] = useState(0);
  const results = useMemo(() => {
    if (selected || query.trim() === "") return [];
    const q = query.trim().toLowerCase();
    return mockStockSearch
      .filter(
        (s) =>
          s.stockName.toLowerCase().includes(q) || s.stockCode.includes(q),
      )
      .slice(0, 10);
  }, [query, selected]);

  useEffect(() => {
    setActiveIdx(0);
  }, [query]);

  if (selected) return null;

  return (
    <div className="relative">
      <input
        type="text"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => {
          if (results.length === 0) return;
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setActiveIdx((i) => Math.min(i + 1, results.length - 1));
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            setActiveIdx((i) => Math.max(i - 1, 0));
          } else if (e.key === "Enter") {
            e.preventDefault();
            onSelect(results[activeIdx]);
          }
        }}
        placeholder="🔍 종목명 또는 코드 입력 (예: 삼성전자 / 005930)"
        className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-zinc-100 focus:outline-none focus:border-emerald-700"
      />
      {results.length > 0 && (
        <div className="absolute z-10 left-0 right-0 mt-1 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl max-h-60 overflow-y-auto">
          {results.map((s, i) => (
            <button
              key={s.stockCode}
              onClick={() => onSelect(s)}
              onMouseEnter={() => setActiveIdx(i)}
              className={`w-full text-left px-3 py-2 text-sm flex justify-between ${
                i === activeIdx ? "bg-zinc-800" : "hover:bg-zinc-800/50"
              }`}
            >
              <span>{s.stockName}</span>
              <span className="text-zinc-500">{s.stockCode}</span>
            </button>
          ))}
        </div>
      )}
      <p className="text-xs text-zinc-500 mt-1">
        ↑/↓로 이동, Enter로 선택
      </p>
    </div>
  );
}

function PriceDisplay({
  stock,
  currentPrice,
  asOf,
  onRefresh,
  refreshKey,
  onClear,
}: {
  stock: StockSearchResult;
  currentPrice: number;
  asOf?: string;
  onRefresh: () => void;
  refreshKey: number;
  onClear: () => void;
}) {
  return (
    <div
      key={refreshKey}
      className="bg-zinc-950 border border-zinc-800 rounded p-3 flex items-center justify-between"
    >
      <div>
        <div className="text-sm font-medium">
          {stock.stockName}
          <span className="text-xs text-zinc-500 ml-2">{stock.stockCode}</span>
        </div>
        <div className="text-xs text-zinc-400 mt-1">
          현재가{" "}
          <span className="text-zinc-100 font-medium">
            {formatPrice(currentPrice)}원
          </span>
          {asOf && (
            <span className="ml-2 text-zinc-500">기준 {formatRelative(asOf)}</span>
          )}
        </div>
      </div>
      <div className="flex items-center gap-1">
        <button
          onClick={onRefresh}
          className="p-1.5 text-zinc-500 hover:text-zinc-200 hover:bg-zinc-800 rounded"
          title="가격 갱신"
        >
          ↻
        </button>
        <button
          onClick={onClear}
          className="p-1.5 text-zinc-500 hover:text-zinc-200 hover:bg-zinc-800 rounded"
          title="다시 선택"
        >
          ×
        </button>
      </div>
    </div>
  );
}

function AmountInput({
  value,
  onChange,
  disabled,
}: {
  value: number;
  onChange: (v: number) => void;
  disabled?: boolean;
}) {
  const [text, setText] = useState(formatComma(value));

  useEffect(() => {
    setText(formatComma(value));
  }, [value]);

  return (
    <div className="relative">
      <input
        type="text"
        inputMode="numeric"
        value={text}
        onChange={(e) => {
          const raw = e.target.value.replace(/[^0-9]/g, "");
          setText(raw === "" ? "" : formatComma(Number(raw)));
          onChange(raw === "" ? 0 : Number(raw));
        }}
        disabled={disabled}
        className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-zinc-100 focus:outline-none focus:border-emerald-700 disabled:opacity-50"
      />
      <span className="absolute right-3 top-1/2 -translate-y-1/2 text-zinc-500 text-sm">
        원
      </span>
    </div>
  );
}

function formatComma(v: number): string {
  return new Intl.NumberFormat("en-US").format(v);
}

function AmountPreview({
  perBuyAmount,
  totalReserve,
  estimatedQty,
  actualBuyEstimate,
  currentPrice,
  availableBalance,
  insufficientBalance,
  belowOneShare,
}: {
  perBuyAmount: number;
  totalReserve: number;
  estimatedQty: number;
  actualBuyEstimate: number;
  currentPrice: number;
  availableBalance: number;
  insufficientBalance: boolean;
  belowOneShare: boolean;
}) {
  return (
    <div className="bg-zinc-950 border border-zinc-800 rounded p-3 text-sm space-y-1">
      <div className="flex justify-between">
        <span className="text-zinc-500">예상 매수 (1회)</span>
        <span className="text-zinc-200">
          {formatQty(estimatedQty)} × {formatPrice(currentPrice)}
          {" = "}
          <span className="font-medium">{formatKRW(actualBuyEstimate)}</span>
        </span>
      </div>
      <div className="flex justify-between">
        <span className="text-zinc-500">3회 총 예약</span>
        <span className="text-zinc-200 font-medium">
          {formatKRW(totalReserve)}
        </span>
      </div>
      <div className="pt-1 mt-1 border-t border-zinc-800">
        {belowOneShare ? (
          <ErrorMsg
            code="PRICE_BELOW_ONE_SHARE"
            extra={`현재가 ${formatPrice(currentPrice)}원 이상 필요`}
          />
        ) : insufficientBalance ? (
          <ErrorMsg
            code="INSUFFICIENT_BALANCE"
            extra={`사용 가능 ${formatKRW(availableBalance)}`}
          />
        ) : perBuyAmount < 10_000 ? (
          <p className="text-xs text-rose-300">최소 10,000원</p>
        ) : (
          <p className="text-xs text-emerald-400">✓ 잔고 한도 내</p>
        )}
      </div>
    </div>
  );
}

function AdvancedSettings({
  value,
  onChange,
  dirtyKeys,
}: {
  value: Advanced;
  onChange: (v: Advanced) => void;
  dirtyKeys: Set<keyof Advanced>;
}) {
  const reset = () => onChange(DEFAULTS);
  return (
    <div className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-3 p-4 bg-zinc-950 border border-zinc-800 rounded">
      <NumField
        label="매수 간격 (분)"
        defaultValue={DEFAULTS.buyIntervalMin}
        value={value.buyIntervalMin}
        dirty={dirtyKeys.has("buyIntervalMin")}
        onChange={(v) => onChange({ ...value, buyIntervalMin: v })}
        step={1}
      />
      <NumField
        label="분할 매도 비율 (%)"
        defaultValue={DEFAULTS.splitSellRatio}
        value={value.splitSellRatio}
        dirty={dirtyKeys.has("splitSellRatio")}
        onChange={(v) => onChange({ ...value, splitSellRatio: v })}
        step={1}
      />
      <NumField
        label="중도 익절 (%)"
        defaultValue={DEFAULTS.midwayProfitPct}
        value={value.midwayProfitPct}
        dirty={dirtyKeys.has("midwayProfitPct")}
        onChange={(v) => onChange({ ...value, midwayProfitPct: v })}
        step={0.1}
      />
      <NumField
        label="본전 매도 기준 (%)"
        defaultValue={DEFAULTS.breakevenThresholdPct}
        value={value.breakevenThresholdPct}
        dirty={dirtyKeys.has("breakevenThresholdPct")}
        onChange={(v) => onChange({ ...value, breakevenThresholdPct: v })}
        step={0.1}
      />
      <NumField
        label="손절 (%)"
        defaultValue={DEFAULTS.stopLossPct}
        value={value.stopLossPct}
        dirty={dirtyKeys.has("stopLossPct")}
        onChange={(v) => onChange({ ...value, stopLossPct: v })}
        step={0.1}
      />
      <div className="md:col-span-2 flex justify-end">
        <button
          onClick={reset}
          disabled={dirtyKeys.size === 0}
          className="text-xs text-zinc-500 hover:text-zinc-300 disabled:opacity-40"
        >
          기본값으로 리셋
        </button>
      </div>
    </div>
  );
}

function NumField({
  label,
  value,
  defaultValue,
  dirty,
  onChange,
  step,
}: {
  label: string;
  value: number;
  defaultValue: number;
  dirty: boolean;
  onChange: (v: number) => void;
  step: number;
}) {
  return (
    <label className="block">
      <span
        className={`text-xs flex items-center gap-1 mb-1 ${
          dirty ? "text-amber-300" : "text-zinc-400"
        }`}
      >
        {dirty && <span className="text-amber-400">●</span>}
        {label}
      </span>
      <input
        type="number"
        value={value}
        step={step}
        onChange={(e) => onChange(Number(e.target.value))}
        placeholder={String(defaultValue)}
        className="w-full bg-zinc-900 border border-zinc-800 rounded px-2 py-1.5 text-sm text-zinc-100 focus:outline-none focus:border-emerald-700"
      />
    </label>
  );
}

// ============================================================
// Sidebar
// ============================================================

function BalancePanel({
  balance,
  insufficient,
}: {
  balance: typeof mockBalance;
  insufficient: boolean;
}) {
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3">
        잔고
      </h3>
      <Row label="가용 현금" value={formatKRW(balance.cashBalance)} />
      <Row
        label="활성 예약"
        value={`-${formatKRW(balance.reservedAmount)}`}
        valueClass="text-zinc-400"
      />
      <div className="border-t border-zinc-800 mt-2 pt-2">
        <Row
          label="사용 가능"
          value={
            <FlashOnChange value={balance.availableBalance}>
              {formatKRW(balance.availableBalance)}
            </FlashOnChange>
          }
          valueClass={
            insufficient
              ? "text-rose-300 font-semibold"
              : "text-emerald-300 font-semibold"
          }
        />
      </div>
      {balance.reservedAmount > 0 && (
        <p className="text-xs text-zinc-500 mt-2">
          활성 명령 {mockActiveCommands.length}개로 예약됨
        </p>
      )}
    </div>
  );
}

function SystemPanel({ status }: { status: SystemStatus }) {
  const conditions = [
    { label: "거래시간 09:00–15:30", ok: status.tradingHoursOpen },
    { label: "휴장 아님", ok: !status.isHoliday },
    { label: "컷오프 전 (15:20)", ok: !status.cutoffPassed },
    { label: "토큰 정상", ok: status.tokenStatus === "OK" },
    {
      label: `시세 모드 ${status.marketMode}`,
      ok: status.marketMode === "WS",
    },
  ];
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3">
        시스템
      </h3>
      <div className="space-y-1.5">
        {conditions.map((c) => (
          <div key={c.label} className="flex items-center gap-2 text-sm">
            <span
              className={`w-1.5 h-1.5 rounded-full ${c.ok ? "bg-emerald-400" : "bg-rose-400"}`}
            />
            <span className={c.ok ? "text-zinc-300" : "text-rose-300"}>
              {c.label}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

function ActiveCommandsPreview({
  commands,
  highlightStock,
}: {
  commands: typeof mockActiveCommands;
  highlightStock?: string;
}) {
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3">
        활성 명령 ({commands.length})
      </h3>
      {commands.length === 0 ? (
        <p className="text-xs text-zinc-600">없음</p>
      ) : (
        <div className="space-y-2">
          {commands.map((c) => {
            const isHighlight = highlightStock === c.stockCode;
            return (
              <Link
                key={c.commandId}
                to="/monitoring"
                className={`flex items-center justify-between gap-2 p-2 rounded text-xs hover:bg-zinc-800 ${
                  isHighlight ? "bg-rose-950/40 border border-rose-800/60" : ""
                }`}
              >
                <span className="flex items-center gap-2 min-w-0">
                  <StatusPill status={c.status} />
                  <span className="truncate">{c.stockName}</span>
                </span>
                <ProfitText value={c.profitRate} format={formatPct} />
              </Link>
            );
          })}
        </div>
      )}
      <p className="text-xs text-zinc-500 mt-3">
        같은 종목 중복 명령은 거부됩니다
      </p>
    </div>
  );
}

// ============================================================
// Helpers
// ============================================================

function Row({
  label,
  value,
  valueClass,
}: {
  label: string;
  value: React.ReactNode;
  valueClass?: string;
}) {
  return (
    <div className="flex justify-between items-baseline py-1.5 text-sm">
      <span className="text-zinc-500">{label}</span>
      <span className={valueClass ?? "text-zinc-200"}>{value}</span>
    </div>
  );
}

function ErrorMsg({ code, extra }: { code: ErrorCode; extra?: string }) {
  return (
    <p className="text-xs text-rose-300">
      · {errorMessage(code)}
      {extra && <span className="ml-1 text-rose-400">({extra})</span>}
    </p>
  );
}

