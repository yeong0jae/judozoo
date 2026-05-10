import { forwardRef, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useForm, type SubmitHandler } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useQueryClient } from "@tanstack/react-query";
import type {
  AccountBalance,
  ErrorCode,
  MarketStatus,
  StockSearchResult,
  TradingSummary,
} from "../types";
import {
  formatKRW,
  formatPct,
  formatPrice,
  formatQty,
  formatRelative,
} from "../lib/format";
import { errorMessage } from "../lib/errorMessages";
import StatusPill from "../components/common/StatusPill";
import ProfitText from "../components/common/ProfitText";
import FlashOnChange from "../components/common/FlashOnChange";
import Skeleton from "../components/common/Skeleton";
import { useToast } from "../components/toast/Toast";
import {
  QK,
  useAccountBalance,
  useActiveCommands,
  useStockPrice,
  useMarketStatus,
  useStockSearch,
} from "../api/queries";
import { useCreateCommand } from "../api/mutations";
import { ApiError } from "../api/client";
import { useStompSubscription } from "../ws/useStompSubscription";

const DEFAULTS = {
  buyIntervalMin: 3,
  splitSellRatio: 20,
  midwayProfitPct: 3,
  breakevenThresholdPct: 2,
  stopLossPct: -2,
};

const schema = z.object({
  perBuyAmount: z
    .number({ message: "숫자를 입력하세요" })
    .min(10_000, "최소 10,000원"),
  buyIntervalMin: z.number().min(1).max(30).nullable().optional(),
  splitSellRatio: z.number().min(1).max(50).nullable().optional(),
  midwayProfitPct: z.number().min(0.1).max(10).nullable().optional(),
  breakevenThresholdPct: z.number().min(0.1).max(10).nullable().optional(),
  stopLossPct: z.number().max(-0.1).min(-10).nullable().optional(),
});
type FormValues = z.infer<typeof schema>;

export default function CommandPage() {
  const toast = useToast();
  const qc = useQueryClient();

  const systemQ = useMarketStatus();
  const balanceQ = useAccountBalance();
  const activeQ = useActiveCommands();

  const [query, setQuery] = useState("");
  const [selectedStock, setSelectedStock] =
    useState<StockSearchResult | null>(null);
  const [serverError, setServerError] = useState<ErrorCode | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(false);

  const stockSearchQ = useStockSearch(query);
  const priceQ = useStockPrice(selectedStock?.stockCode ?? null);

  const createCommand = useCreateCommand();

  const {
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      perBuyAmount: 1_000_000,
      buyIntervalMin: null,
      splitSellRatio: null,
      midwayProfitPct: null,
      breakevenThresholdPct: null,
      stopLossPct: null,
    },
  });

  const formValues = watch();
  const perBuyAmount = formValues.perBuyAmount ?? 0;

  // STOMP 구독: 시장 변경
  useStompSubscription("/topic/market", () => {
    qc.invalidateQueries({ queryKey: QK.marketStatus });
  });

  const status = systemQ.data;
  const balance = balanceQ.data;
  const block = status ? deriveBlock(status) : null;

  const currentPrice = priceQ.data?.currentPrice ?? 0;
  const estimatedQty =
    currentPrice > 0 ? Math.floor(perBuyAmount / currentPrice) : 0;
  const totalReserve = perBuyAmount * 3;
  const totalActualBuyEstimate = currentPrice * estimatedQty;
  const insufficientBalance =
    balance !== undefined && totalReserve > balance.availableBalance;
  const belowOneShare =
    currentPrice > 0 && perBuyAmount > 0 && perBuyAmount < currentPrice;
  const duplicateActive =
    selectedStock !== null &&
    (activeQ.data ?? []).some(
      (c) => c.stockCode === selectedStock.stockCode,
    );

  const advancedDirty = useMemo(() => {
    const dirty = new Set<string>();
    if (formValues.buyIntervalMin != null) dirty.add("buyIntervalMin");
    if (formValues.splitSellRatio != null) dirty.add("splitSellRatio");
    if (formValues.midwayProfitPct != null) dirty.add("midwayProfitPct");
    if (formValues.breakevenThresholdPct != null)
      dirty.add("breakevenThresholdPct");
    if (formValues.stopLossPct != null) dirty.add("stopLossPct");
    return dirty;
  }, [formValues]);

  const submitDisabled =
    !!block ||
    !selectedStock ||
    insufficientBalance ||
    belowOneShare ||
    perBuyAmount < 10_000 ||
    createCommand.isPending;

  const onSubmit: SubmitHandler<FormValues> = async (data) => {
    setServerError(null);
    if (!selectedStock) return;
    if (duplicateActive) {
      setServerError("DUPLICATE_COMMAND");
      return;
    }
    try {
      await createCommand.mutateAsync({
        stockCode: selectedStock.stockCode,
        perBuyAmount: data.perBuyAmount,
        buyIntervalMin: data.buyIntervalMin ?? null,
        splitSellRatio:
          data.splitSellRatio != null ? data.splitSellRatio / 100 : null,
        midwayProfitPct: data.midwayProfitPct ?? null,
        breakevenThresholdPct: data.breakevenThresholdPct ?? null,
        stopLossPct: data.stopLossPct ?? null,
      });
      qc.invalidateQueries({ queryKey: QK.accountBalance });
      toast.show({
        tone: "success",
        message: `${selectedStock.stockName} 매매가 시작되었습니다`,
        action: {
          label: "모니터링에서 확인 →",
          onClick: () => window.location.assign("/monitoring"),
        },
      });
      setQuery("");
      setSelectedStock(null);
      reset();
      setAdvancedOpen(false);
    } catch (e) {
      if (e instanceof ApiError) {
        setServerError(e.code as ErrorCode);
      } else {
        toast.show({
          tone: "error",
          message: errorMessage("NETWORK_ERROR"),
        });
      }
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
      {block && <BlockBanner reason={block} />}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <section className="lg:col-span-2 bg-zinc-900 border border-zinc-800 rounded-lg p-6 space-y-6">
          <h2 className="text-lg font-semibold">새 매매 명령</h2>

          <Field
            number={1}
            label="종목"
            error={
              serverError === "STOCK_NOT_FOUND" ||
              serverError === "DUPLICATE_COMMAND"
            }
          >
            <StockSearchInput
              query={query}
              setQuery={setQuery}
              selected={selectedStock}
              results={stockSearchQ.data ?? []}
              loading={stockSearchQ.isFetching}
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
                asOf={priceQ.data?.asOf}
                loading={priceQ.isFetching}
                onRefresh={() =>
                  qc.invalidateQueries({
                    queryKey: QK.stockPrice(selectedStock.stockCode),
                  })
                }
                onClear={() => setSelectedStock(null)}
              />
            )}
            {duplicateActive && <ErrorMsg code="DUPLICATE_COMMAND" />}
            {serverError === "STOCK_NOT_FOUND" && (
              <ErrorMsg code="STOCK_NOT_FOUND" />
            )}
          </Field>

          <Field
            number={2}
            label="1회 매수금액"
            disabled={!selectedStock}
            error={
              !!errors.perBuyAmount ||
              insufficientBalance ||
              belowOneShare ||
              serverError === "PRICE_BELOW_ONE_SHARE" ||
              serverError === "INSUFFICIENT_BALANCE"
            }
          >
            <AmountInput
              {...register("perBuyAmount", { valueAsNumber: true })}
              disabled={!selectedStock}
            />
            {selectedStock && (
              <AmountPreview
                perBuyAmount={perBuyAmount}
                totalReserve={totalReserve}
                estimatedQty={estimatedQty}
                actualBuyEstimate={totalActualBuyEstimate}
                currentPrice={currentPrice}
                availableBalance={balance?.availableBalance}
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
                {advancedDirty.size === 0
                  ? "기본값 사용 중"
                  : `${advancedDirty.size}개 항목 변경됨`}
              </span>
            </button>
            {advancedOpen && (
              <AdvancedSettings
                register={register}
                dirtyKeys={advancedDirty}
                onReset={() => {
                  reset({
                    perBuyAmount: formValues.perBuyAmount,
                    buyIntervalMin: null,
                    splitSellRatio: null,
                    midwayProfitPct: null,
                    breakevenThresholdPct: null,
                    stopLossPct: null,
                  });
                }}
              />
            )}
          </Field>

          <button
            type="submit"
            disabled={submitDisabled}
            className="w-full bg-emerald-700 hover:bg-emerald-600 disabled:bg-zinc-800 disabled:text-zinc-600 text-white px-4 py-3 rounded-md font-medium transition-colors"
          >
            {createCommand.isPending ? "전송 중..." : "매매 시작"}
          </button>

          {serverError &&
            !["STOCK_NOT_FOUND", "DUPLICATE_COMMAND"].includes(
              serverError,
            ) && <ErrorMsg code={serverError} />}
        </section>

        <aside className="space-y-6">
          <BalancePanel
            balance={balance}
            loading={balanceQ.isLoading}
            error={balanceQ.isError}
            errorCode={
              balanceQ.error instanceof ApiError
                ? balanceQ.error.code
                : undefined
            }
            onRetry={() => balanceQ.refetch()}
            insufficient={insufficientBalance}
          />
          <SystemPanel status={status} loading={systemQ.isLoading} />
          <ActiveCommandsPreview
            commands={activeQ.data ?? []}
            highlightStock={selectedStock?.stockCode}
            loading={activeQ.isLoading}
          />
        </aside>
      </div>
    </form>
  );
}

// ============================================================
// Block banner
// ============================================================

function deriveBlock(s: MarketStatus): {
  code: ErrorCode;
  message: string;
  tone: "warn" | "danger";
} | null {
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
  results,
  loading,
  onSelect,
}: {
  query: string;
  setQuery: (v: string) => void;
  selected: StockSearchResult | null;
  results: StockSearchResult[];
  loading: boolean;
  onSelect: (s: StockSearchResult) => void;
}) {
  const [activeIdx, setActiveIdx] = useState(0);

  useEffect(() => {
    setActiveIdx(0);
  }, [query]);

  if (selected) return null;

  const visible = results.slice(0, 10);
  const showDropdown = query.trim() !== "" && (loading || visible.length > 0);

  return (
    <div className="relative">
      <input
        type="text"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => {
          if (visible.length === 0) return;
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setActiveIdx((i) => Math.min(i + 1, visible.length - 1));
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            setActiveIdx((i) => Math.max(i - 1, 0));
          } else if (e.key === "Enter") {
            e.preventDefault();
            onSelect(visible[activeIdx]);
          }
        }}
        placeholder="🔍 종목명 또는 코드 입력 (예: 삼성전자 / 005930)"
        className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-zinc-100 focus:outline-none focus:border-emerald-700"
      />
      {showDropdown && (
        <div className="absolute z-10 left-0 right-0 mt-1 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl max-h-60 overflow-y-auto">
          {loading && (
            <div className="px-3 py-2 text-xs text-zinc-500">검색 중...</div>
          )}
          {visible.map((s, i) => (
            <button
              key={s.stockCode}
              type="button"
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
      <p className="text-xs text-zinc-500 mt-1">↑/↓로 이동, Enter로 선택</p>
    </div>
  );
}

function PriceDisplay({
  stock,
  currentPrice,
  asOf,
  loading,
  onRefresh,
  onClear,
}: {
  stock: StockSearchResult;
  currentPrice: number;
  asOf?: string;
  loading: boolean;
  onRefresh: () => void;
  onClear: () => void;
}) {
  return (
    <div className="bg-zinc-950 border border-zinc-800 rounded p-3 flex items-center justify-between">
      <div>
        <div className="text-sm font-medium">
          {stock.stockName}
          <span className="text-xs text-zinc-500 ml-2">{stock.stockCode}</span>
        </div>
        <div className="text-xs text-zinc-400 mt-1">
          현재가{" "}
          <span className="text-zinc-100 font-medium">
            {loading ? "..." : `${formatPrice(currentPrice)}원`}
          </span>
          {asOf && (
            <span className="ml-2 text-zinc-500">
              기준 {formatRelative(asOf)}
            </span>
          )}
        </div>
      </div>
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={onRefresh}
          disabled={loading}
          className="p-1.5 text-zinc-500 hover:text-zinc-200 hover:bg-zinc-800 rounded disabled:opacity-40"
          title="가격 갱신"
        >
          ↻
        </button>
        <button
          type="button"
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

const AmountInput = forwardRef<
  HTMLInputElement,
  React.InputHTMLAttributes<HTMLInputElement>
>((props, ref) => (
  <div className="relative">
    <input
      ref={ref}
      type="number"
      step={1}
      min={0}
      {...props}
      className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-zinc-100 focus:outline-none focus:border-emerald-700 disabled:opacity-50 [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
    />
    <span className="absolute right-3 top-1/2 -translate-y-1/2 text-zinc-500 text-sm pointer-events-none">
      원
    </span>
  </div>
));
AmountInput.displayName = "AmountInput";

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
  availableBalance?: number;
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
        ) : insufficientBalance && availableBalance !== undefined ? (
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
  register,
  dirtyKeys,
  onReset,
}: {
  register: ReturnType<typeof useForm<FormValues>>["register"];
  dirtyKeys: Set<string>;
  onReset: () => void;
}) {
  return (
    <div className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-3 p-4 bg-zinc-950 border border-zinc-800 rounded">
      <NumField
        label="매수 간격 (분)"
        defaultValue={DEFAULTS.buyIntervalMin}
        dirty={dirtyKeys.has("buyIntervalMin")}
        registration={register("buyIntervalMin", {
          setValueAs: (v) => (v === "" || v == null ? null : Number(v)),
        })}
        step={1}
      />
      <NumField
        label="분할 매도 비율 (%)"
        defaultValue={DEFAULTS.splitSellRatio}
        dirty={dirtyKeys.has("splitSellRatio")}
        registration={register("splitSellRatio", {
          setValueAs: (v) => (v === "" || v == null ? null : Number(v)),
        })}
        step={1}
      />
      <NumField
        label="중도 익절 (%)"
        defaultValue={DEFAULTS.midwayProfitPct}
        dirty={dirtyKeys.has("midwayProfitPct")}
        registration={register("midwayProfitPct", {
          setValueAs: (v) => (v === "" || v == null ? null : Number(v)),
        })}
        step={0.1}
      />
      <NumField
        label="본전 매도 기준 (%)"
        defaultValue={DEFAULTS.breakevenThresholdPct}
        dirty={dirtyKeys.has("breakevenThresholdPct")}
        registration={register("breakevenThresholdPct", {
          setValueAs: (v) => (v === "" || v == null ? null : Number(v)),
        })}
        step={0.1}
      />
      <NumField
        label="손절 (%)"
        defaultValue={DEFAULTS.stopLossPct}
        dirty={dirtyKeys.has("stopLossPct")}
        registration={register("stopLossPct", {
          setValueAs: (v) => (v === "" || v == null ? null : Number(v)),
        })}
        step={0.1}
      />
      <div className="md:col-span-2 flex justify-end">
        <button
          type="button"
          onClick={onReset}
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
  defaultValue,
  dirty,
  registration,
  step,
}: {
  label: string;
  defaultValue: number;
  dirty: boolean;
  registration: ReturnType<ReturnType<typeof useForm<FormValues>>["register"]>;
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
        step={step}
        placeholder={String(defaultValue)}
        {...registration}
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
  loading,
  error,
  errorCode,
  onRetry,
  insufficient,
}: {
  balance: AccountBalance | undefined;
  loading: boolean;
  error: boolean;
  errorCode?: string;
  onRetry: () => void;
  insufficient: boolean;
}) {
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3">
        잔고
      </h3>
      {loading ? (
        <div className="space-y-2">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-5 w-full" />
        </div>
      ) : error || !balance ? (
        <div className="text-sm text-rose-300 space-y-2">
          <p>
            잔고 조회 실패
            {errorCode && (
              <span className="text-xs text-rose-400 block mt-1">
                ({errorCode})
              </span>
            )}
          </p>
          <button
            onClick={onRetry}
            className="text-xs text-zinc-300 hover:text-white px-2 py-1 rounded bg-zinc-800 border border-zinc-700"
          >
            다시 시도
          </button>
        </div>
      ) : (
        <>
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
        </>
      )}
    </div>
  );
}

function SystemPanel({
  status,
  loading,
}: {
  status: MarketStatus | undefined;
  loading: boolean;
}) {
  if (loading || !status) {
    return (
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4 space-y-2">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-4 w-full" />
        ))}
      </div>
    );
  }
  const conditions = [
    { label: "거래시간 09:00–15:30", ok: status.tradingHoursOpen },
    { label: "휴장 아님", ok: !status.isHoliday },
    { label: "컷오프 전 (15:20)", ok: !status.cutoffPassed },
  ];
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3">
        시장 상태
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
  loading,
}: {
  commands: TradingSummary[];
  highlightStock?: string;
  loading: boolean;
}) {
  return (
    <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3">
        활성 명령 ({loading ? "..." : commands.length})
      </h3>
      {loading ? (
        <div className="space-y-2">
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
        </div>
      ) : commands.length === 0 ? (
        <p className="text-xs text-zinc-600">없음</p>
      ) : (
        <div className="space-y-2">
          {commands.map((c) => {
            const isHighlight = highlightStock === c.stockCode;
            return (
              <Link
                key={c.cycleId}
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
