import { forwardRef, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
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
  STOCK_SEARCH_MIN_LEN,
  useAccountBalance,
  useActiveCommands,
  useStockPrice,
  useMarketStatus,
  useStockSearch,
} from "../api/queries";
import { useDebounce } from "../hooks/useDebounce";
import { useCreateCommand } from "../api/mutations";
import { ApiError } from "../api/client";
import { useStompSubscription } from "../ws/useStompSubscription";

const DEFAULTS = {
  splitSellRatio: 20,
  breakevenThresholdPct: 2,
  stopLossPct: 2, // 양수로 저장, 표시만 -X%
};

const schema = z.object({
  perBuyQty: z
    .number({ message: "숫자를 입력하세요" })
    .int("정수를 입력하세요")
    .min(1, "최소 1주"),
  splitSellRatio: z.number().min(1).max(50).nullable().optional(),
  breakevenThresholdPct: z.number().min(0.1).max(10).nullable().optional(),
  // 백엔드 계약: 양수 입력(내부에서 음수 변환). 표시만 -X%, 전송은 +X.
  stopLossPct: z.number().min(0.1).max(10).nullable().optional(),
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

  // 주도주 페이지 등에서 ?stockCode=XXXXXX&stockName=… 으로 prefill 진입한 경우
  // 한 번 읽어 selectedStock에 세팅하고 URL을 정리한다.
  const [searchParams, setSearchParams] = useSearchParams();
  useEffect(() => {
    const code = searchParams.get("stockCode");
    const name = searchParams.get("stockName");
    if (code && name && !selectedStock) {
      setSelectedStock({ stockCode: code, stockName: name });
      setSearchParams({}, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  const debouncedQuery = useDebounce(query, 250);
  const stockSearchQ = useStockSearch(debouncedQuery);
  const priceQ = useStockPrice(selectedStock?.stockCode ?? null);

  const createCommand = useCreateCommand();

  const {
    register,
    handleSubmit,
    watch,
    reset,
    setValue,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      perBuyQty: 1,
      splitSellRatio: null,
      breakevenThresholdPct: null,
      stopLossPct: null,
    },
  });

  const formValues = watch();
  const perBuyQty = formValues.perBuyQty ?? 0;

  // STOMP 구독: 시장 변경
  useStompSubscription("/topic/market", () => {
    qc.invalidateQueries({ queryKey: QK.marketStatus });
  });

  const status = systemQ.data;
  const balance = balanceQ.data;
  const block = status ? deriveBlock(status) : null;

  const currentPrice = priceQ.data?.currentPrice ?? 0;
  const perBuyAmountEstimate = currentPrice * perBuyQty;
  const insufficientBalance =
    balance !== undefined && perBuyAmountEstimate > balance.availableBalance;
  const duplicateActive =
    selectedStock !== null &&
    (activeQ.data ?? []).some(
      (c) => c.stockCode === selectedStock.stockCode,
    );

  const advancedDirty = useMemo(() => {
    const dirty = new Set<string>();
    if (formValues.splitSellRatio != null) dirty.add("splitSellRatio");
    if (formValues.breakevenThresholdPct != null)
      dirty.add("breakevenThresholdPct");
    if (formValues.stopLossPct != null) dirty.add("stopLossPct");
    return dirty;
  }, [formValues]);

  const submitDisabled =
    !!block ||
    !selectedStock ||
    insufficientBalance ||
    perBuyQty < 1 ||
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
        perBuyQty: data.perBuyQty,
        splitSellRatio:
          data.splitSellRatio != null ? data.splitSellRatio / 100 : null,
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
          <h2 className="text-xl font-bold">새 매매 명령</h2>

          <Field
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
            label="1회 매수 개수"
            disabled={!selectedStock}
            error={
              !!errors.perBuyQty ||
              insufficientBalance ||
              serverError === "INSUFFICIENT_BALANCE"
            }
          >
            <QtyInput
              {...register("perBuyQty", { valueAsNumber: true })}
              disabled={!selectedStock}
            />
            {selectedStock && (
              <QtyPreview
                perBuyQty={perBuyQty}
                perBuyAmountEstimate={perBuyAmountEstimate}
                currentPrice={currentPrice}
                availableBalance={balance?.availableBalance}
                insufficientBalance={insufficientBalance}
              />
            )}
          </Field>

          <Field label="고급 설정">
            <div className="text-xs text-zinc-500">
              {advancedDirty.size === 0
                ? "기본값 사용 중"
                : `${advancedDirty.size}개 항목 변경됨`}
            </div>
            <AdvancedSettings
              values={formValues}
              setValue={setValue}
              dirtyKeys={advancedDirty}
              onReset={() => {
                reset({
                  perBuyQty: formValues.perBuyQty,
                  splitSellRatio: null,
                  breakevenThresholdPct: null,
                  stopLossPct: null,
                });
              }}
            />
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
  return null;
}

function BlockBanner({
  reason,
}: {
  reason: { message: string; tone: "warn" | "danger" };
}) {
  const cls =
    reason.tone === "danger"
      ? "bg-rose-50 border-rose-200 text-rose-700"
      : "bg-amber-50 border-amber-200 text-amber-800";
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
  label,
  children,
  error,
  disabled,
}: {
  label: string;
  children: React.ReactNode;
  error?: boolean;
  disabled?: boolean;
}) {
  return (
    <div className={disabled ? "opacity-50 pointer-events-none" : ""}>
      <label
        className={`block text-base font-semibold mb-2 ${
          error ? "text-rose-700" : "text-zinc-100"
        }`}
      >
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

  const trimmed = query.trim();
  const tooShort = trimmed !== "" && trimmed.length < STOCK_SEARCH_MIN_LEN;
  const visible = results.slice(0, 10);
  const showDropdown =
    trimmed.length >= STOCK_SEARCH_MIN_LEN && (loading || visible.length > 0);

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
        placeholder="🔍 종목명 또는 코드 입력 (예: 삼성전자, 005930)"
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
      <p className="text-xs text-zinc-500 mt-1">
        {tooShort ? "2자 이상 입력하세요" : "↑/↓로 이동, Enter로 선택"}
      </p>
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

const QtyInput = forwardRef<
  HTMLInputElement,
  React.InputHTMLAttributes<HTMLInputElement>
>((props, ref) => (
  <div className="relative">
    <input
      ref={ref}
      type="number"
      step={1}
      min={1}
      {...props}
      className="w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-zinc-100 focus:outline-none focus:border-emerald-700 disabled:opacity-50 [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
    />
    <span className="absolute right-3 top-1/2 -translate-y-1/2 text-zinc-500 text-sm pointer-events-none">
      주
    </span>
  </div>
));
QtyInput.displayName = "QtyInput";

function QtyPreview({
  perBuyQty,
  perBuyAmountEstimate,
  currentPrice,
  availableBalance,
  insufficientBalance,
}: {
  perBuyQty: number;
  perBuyAmountEstimate: number;
  currentPrice: number;
  availableBalance?: number;
  insufficientBalance: boolean;
}) {
  return (
    <div className="bg-zinc-950 border border-zinc-800 rounded p-3 text-sm space-y-1">
      <div className="flex justify-between">
        <span className="text-zinc-500">1회 매수 (현재가 기준)</span>
        <span className="text-zinc-200">
          {formatQty(perBuyQty)} × {formatPrice(currentPrice)}
          {" = "}
          <span className="font-medium">≈ {formatKRW(perBuyAmountEstimate)}</span>
        </span>
      </div>
      <div className="pt-1 mt-1 border-t border-zinc-800">
        {perBuyQty < 1 ? (
          <p className="text-xs text-rose-700">최소 1주</p>
        ) : insufficientBalance && availableBalance !== undefined ? (
          <ErrorMsg
            code="INSUFFICIENT_BALANCE"
            extra={`사용 가능 ${formatKRW(availableBalance)}`}
          />
        ) : (
          <p className="text-xs text-emerald-400">✓ 잔고 한도 내 (추정)</p>
        )}
      </div>
    </div>
  );
}

// 각 고급 옵션의 선택 가능한 값 — 자유 입력 대신 칩 선택.
// 첫 칩이 아니라, DEFAULTS와 일치하는 칩이 "기본값" 표시 대상.
const OPTIONS = {
  splitSellRatio: [10, 20, 30],
  breakevenThresholdPct: [1, 2, 3, 4, 5],
  stopLossPct: [1, 2, 3, 4, 5], // 양수 저장, ChipField가 표시만 "-X%"
} as const;

type AdvancedKey =
  | "splitSellRatio"
  | "breakevenThresholdPct"
  | "stopLossPct";

function AdvancedSettings({
  values,
  setValue,
  dirtyKeys,
  onReset,
}: {
  values: FormValues;
  setValue: ReturnType<typeof useForm<FormValues>>["setValue"];
  dirtyKeys: Set<string>;
  onReset: () => void;
}) {
  const pick = (key: AdvancedKey, v: number | null) =>
    setValue(key, v, { shouldDirty: true });

  return (
    <div className="mt-3 grid grid-cols-1 lg:grid-cols-2 gap-x-8 gap-y-4">
      <ChipField
        label="분할 매도 비율"
        hint="익절 단계 2%/3%/5% 마다 매도할 비율"
        unit="%"
        options={OPTIONS.splitSellRatio}
        defaultValue={DEFAULTS.splitSellRatio}
        value={values.splitSellRatio ?? null}
        dirty={dirtyKeys.has("splitSellRatio")}
        onPick={(v) => pick("splitSellRatio", v)}
      />
      <ChipField
        label="본전 매도 발동 기준"
        hint="이만큼 상승한 후 하락하면 손절이 아닌 본전에 매도"
        unit="%"
        options={OPTIONS.breakevenThresholdPct}
        defaultValue={DEFAULTS.breakevenThresholdPct}
        value={values.breakevenThresholdPct ?? null}
        dirty={dirtyKeys.has("breakevenThresholdPct")}
        onPick={(v) => pick("breakevenThresholdPct", v)}
      />
      <ChipField
        label="손절"
        hint="이만큼 떨어지면 즉시 전량 매도"
        unit="%"
        options={OPTIONS.stopLossPct}
        defaultValue={DEFAULTS.stopLossPct}
        value={values.stopLossPct ?? null}
        dirty={dirtyKeys.has("stopLossPct")}
        onPick={(v) => pick("stopLossPct", v)}
        formatOption={(v) => `-${v}%`}
      />
      <div className="lg:col-span-2 flex justify-end">
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

function ChipField({
  label,
  hint,
  unit,
  options,
  defaultValue,
  value,
  dirty,
  onPick,
  formatOption,
}: {
  label: string;
  hint?: string;
  unit: string;
  options: readonly number[];
  defaultValue: number;
  value: number | null;
  dirty: boolean;
  onPick: (v: number | null) => void;
  /** 표시 텍스트 커스터마이즈 (e.g. 양수 저장, "-X%"로 표시). 기본은 `${opt}${unit}`. */
  formatOption?: (opt: number) => string;
}) {
  const display = (opt: number) => formatOption?.(opt) ?? `${opt}${unit}`;
  // 칩 active 판정 — 명시값이면 그 값, null이면 기본값 칩이 active.
  const activeValue = value ?? defaultValue;
  return (
    <div>
      <div
        className={`text-sm font-medium flex items-center gap-1 ${
          dirty ? "text-amber-700" : "text-zinc-200"
        }`}
      >
        {dirty && <span className="text-amber-600">●</span>}
        {label}
      </div>
      {hint && <div className="text-xs text-zinc-500 mt-0.5 mb-2">{hint}</div>}
      <div className="flex flex-wrap gap-1.5">
        {options.map((opt) => {
          const isActive = opt === activeValue;
          // 같은 칩을 다시 누르면 null로 비워 "기본값 사용" 상태로 복귀.
          const handleClick = () =>
            onPick(value !== null && value === opt ? null : opt);
          return (
            <button
              key={opt}
              type="button"
              onClick={handleClick}
              className={`px-3 py-1.5 rounded-full text-xs font-medium transition-colors border ${
                isActive
                  ? "bg-emerald-700 text-white border-emerald-700"
                  : "bg-zinc-900 text-zinc-300 border-zinc-800 hover:bg-zinc-800"
              }`}
            >
              {display(opt)}
            </button>
          );
        })}
      </div>
    </div>
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
        <div className="text-sm text-rose-700 space-y-2">
          <p>
            잔고 조회 실패
            {errorCode && (
              <span className="text-xs text-rose-500 block mt-1">
                ({errorCode})
              </span>
            )}
          </p>
          <button
            onClick={onRetry}
            className="text-xs text-zinc-300 hover:text-zinc-100 px-2 py-1 rounded bg-zinc-800 border border-zinc-700"
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
                  ? "text-rose-700 font-semibold"
                  : "text-emerald-800 font-semibold"
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
    { label: "거래시간 08:00–20:00", ok: status.tradingHoursOpen },
    { label: "휴장 아님", ok: !status.isHoliday },
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
            <span className={c.ok ? "text-zinc-300" : "text-rose-700"}>
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
                  isHighlight ? "bg-rose-50 border border-rose-200" : ""
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
    <p className="text-xs text-rose-700">
      · {errorMessage(code)}
      {extra && <span className="ml-1 text-rose-500">({extra})</span>}
    </p>
  );
}
