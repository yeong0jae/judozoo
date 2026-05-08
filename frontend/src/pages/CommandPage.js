import { jsx as _jsx, jsxs as _jsxs, Fragment as _Fragment } from "react/jsx-runtime";
import { forwardRef, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useQueryClient } from "@tanstack/react-query";
import { formatKRW, formatPct, formatPrice, formatQty, formatRelative, } from "../lib/format";
import { errorMessage } from "../lib/errorMessages";
import StatusPill from "../components/common/StatusPill";
import ProfitText from "../components/common/ProfitText";
import FlashOnChange from "../components/common/FlashOnChange";
import Skeleton from "../components/common/Skeleton";
import { useToast } from "../components/toast/Toast";
import { QK, useAccountBalance, useActiveCommands, useStockPrice, useMarketStatus, useStockSearch, } from "../api/queries";
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
export default function CommandPage() {
    const toast = useToast();
    const qc = useQueryClient();
    const systemQ = useMarketStatus();
    const balanceQ = useAccountBalance();
    const activeQ = useActiveCommands();
    const [query, setQuery] = useState("");
    const [selectedStock, setSelectedStock] = useState(null);
    const [serverError, setServerError] = useState(null);
    const [advancedOpen, setAdvancedOpen] = useState(false);
    const stockSearchQ = useStockSearch(query);
    const priceQ = useStockPrice(selectedStock?.stockCode ?? null);
    const createCommand = useCreateCommand();
    const { register, handleSubmit, watch, reset, formState: { errors }, } = useForm({
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
    // STOMP 구독: 잔고 / 시장 변경
    useStompSubscription("/topic/account", () => {
        qc.invalidateQueries({ queryKey: QK.accountBalance });
    });
    useStompSubscription("/topic/market", () => {
        qc.invalidateQueries({ queryKey: QK.marketStatus });
    });
    const status = systemQ.data;
    const balance = balanceQ.data;
    const block = status ? deriveBlock(status) : null;
    const currentPrice = priceQ.data?.currentPrice ?? 0;
    const estimatedQty = currentPrice > 0 ? Math.floor(perBuyAmount / currentPrice) : 0;
    const totalReserve = perBuyAmount * 3;
    const totalActualBuyEstimate = currentPrice * estimatedQty;
    const insufficientBalance = balance !== undefined && totalReserve > balance.availableBalance;
    const belowOneShare = currentPrice > 0 && perBuyAmount > 0 && perBuyAmount < currentPrice;
    const duplicateActive = selectedStock !== null &&
        (activeQ.data ?? []).some((c) => c.stockCode === selectedStock.stockCode);
    const advancedDirty = useMemo(() => {
        const dirty = new Set();
        if (formValues.buyIntervalMin != null)
            dirty.add("buyIntervalMin");
        if (formValues.splitSellRatio != null)
            dirty.add("splitSellRatio");
        if (formValues.midwayProfitPct != null)
            dirty.add("midwayProfitPct");
        if (formValues.breakevenThresholdPct != null)
            dirty.add("breakevenThresholdPct");
        if (formValues.stopLossPct != null)
            dirty.add("stopLossPct");
        return dirty;
    }, [formValues]);
    const submitDisabled = !!block ||
        !selectedStock ||
        insufficientBalance ||
        belowOneShare ||
        perBuyAmount < 10_000 ||
        createCommand.isPending;
    const onSubmit = async (data) => {
        setServerError(null);
        if (!selectedStock)
            return;
        if (duplicateActive) {
            setServerError("DUPLICATE_COMMAND");
            return;
        }
        try {
            await createCommand.mutateAsync({
                stockCode: selectedStock.stockCode,
                perBuyAmount: data.perBuyAmount,
                buyIntervalMin: data.buyIntervalMin ?? null,
                splitSellRatio: data.splitSellRatio != null ? data.splitSellRatio / 100 : null,
                midwayProfitPct: data.midwayProfitPct ?? null,
                breakevenThresholdPct: data.breakevenThresholdPct ?? null,
                stopLossPct: data.stopLossPct ?? null,
            });
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
        }
        catch (e) {
            if (e instanceof ApiError) {
                setServerError(e.code);
            }
            else {
                toast.show({
                    tone: "error",
                    message: errorMessage("NETWORK_ERROR"),
                });
            }
        }
    };
    return (_jsxs("form", { onSubmit: handleSubmit(onSubmit), className: "space-y-6", children: [block && _jsx(BlockBanner, { reason: block }), _jsxs("div", { className: "grid grid-cols-1 lg:grid-cols-3 gap-6", children: [_jsxs("section", { className: "lg:col-span-2 bg-zinc-900 border border-zinc-800 rounded-lg p-6 space-y-6", children: [_jsx("h2", { className: "text-lg font-semibold", children: "\uC0C8 \uB9E4\uB9E4 \uBA85\uB839" }), _jsxs(Field, { number: 1, label: "\uC885\uBAA9", error: serverError === "STOCK_NOT_FOUND" ||
                                    serverError === "DUPLICATE_COMMAND", children: [_jsx(StockSearchInput, { query: query, setQuery: setQuery, selected: selectedStock, results: stockSearchQ.data ?? [], loading: stockSearchQ.isFetching, onSelect: (s) => {
                                            setSelectedStock(s);
                                            setQuery("");
                                            setServerError(null);
                                        } }), selectedStock && (_jsx(PriceDisplay, { stock: selectedStock, currentPrice: currentPrice, asOf: priceQ.data?.asOf, loading: priceQ.isFetching, onRefresh: () => qc.invalidateQueries({
                                            queryKey: QK.stockPrice(selectedStock.stockCode),
                                        }), onClear: () => setSelectedStock(null) })), duplicateActive && _jsx(ErrorMsg, { code: "DUPLICATE_COMMAND" }), serverError === "STOCK_NOT_FOUND" && (_jsx(ErrorMsg, { code: "STOCK_NOT_FOUND" }))] }), _jsxs(Field, { number: 2, label: "1\uD68C \uB9E4\uC218\uAE08\uC561", disabled: !selectedStock, error: !!errors.perBuyAmount ||
                                    insufficientBalance ||
                                    belowOneShare ||
                                    serverError === "PRICE_BELOW_ONE_SHARE" ||
                                    serverError === "INSUFFICIENT_BALANCE", children: [_jsx(AmountInput, { ...register("perBuyAmount", { valueAsNumber: true }), disabled: !selectedStock }), selectedStock && (_jsx(AmountPreview, { perBuyAmount: perBuyAmount, totalReserve: totalReserve, estimatedQty: estimatedQty, actualBuyEstimate: totalActualBuyEstimate, currentPrice: currentPrice, availableBalance: balance?.availableBalance, insufficientBalance: insufficientBalance, belowOneShare: belowOneShare }))] }), _jsxs(Field, { number: 3, label: "\uACE0\uAE09 \uC124\uC815", children: [_jsxs("button", { type: "button", onClick: () => setAdvancedOpen((v) => !v), className: "text-sm text-zinc-400 hover:text-zinc-200 flex items-center gap-1", children: [_jsx("span", { children: advancedOpen ? "▾" : "▸" }), _jsx("span", { children: advancedDirty.size === 0
                                                    ? "기본값 사용 중"
                                                    : `${advancedDirty.size}개 항목 변경됨` })] }), advancedOpen && (_jsx(AdvancedSettings, { register: register, dirtyKeys: advancedDirty, onReset: () => {
                                            reset({
                                                perBuyAmount: formValues.perBuyAmount,
                                                buyIntervalMin: null,
                                                splitSellRatio: null,
                                                midwayProfitPct: null,
                                                breakevenThresholdPct: null,
                                                stopLossPct: null,
                                            });
                                        } }))] }), _jsx("button", { type: "submit", disabled: submitDisabled, className: "w-full bg-emerald-700 hover:bg-emerald-600 disabled:bg-zinc-800 disabled:text-zinc-600 text-white px-4 py-3 rounded-md font-medium transition-colors", children: createCommand.isPending ? "전송 중..." : "매매 시작" }), serverError &&
                                !["STOCK_NOT_FOUND", "DUPLICATE_COMMAND"].includes(serverError) && _jsx(ErrorMsg, { code: serverError })] }), _jsxs("aside", { className: "space-y-6", children: [_jsx(BalancePanel, { balance: balance, loading: balanceQ.isLoading, error: balanceQ.isError, errorCode: balanceQ.error instanceof ApiError
                                    ? balanceQ.error.code
                                    : undefined, onRetry: () => balanceQ.refetch(), insufficient: insufficientBalance }), _jsx(SystemPanel, { status: status, loading: systemQ.isLoading }), _jsx(ActiveCommandsPreview, { commands: activeQ.data ?? [], highlightStock: selectedStock?.stockCode, loading: activeQ.isLoading })] })] })] }));
}
// ============================================================
// Block banner
// ============================================================
function deriveBlock(s) {
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
function BlockBanner({ reason, }) {
    const cls = reason.tone === "danger"
        ? "bg-rose-950/60 border-rose-800 text-rose-200"
        : "bg-amber-950/60 border-amber-800 text-amber-200";
    return (_jsxs("div", { className: `border rounded-lg px-4 py-3 text-sm ${cls}`, children: ["\u26D4 ", reason.message] }));
}
// ============================================================
// Form fields
// ============================================================
function Field({ number, label, children, error, disabled, }) {
    return (_jsxs("div", { className: disabled ? "opacity-50 pointer-events-none" : "", children: [_jsxs("label", { className: `text-sm font-medium mb-2 flex items-center gap-2 ${error ? "text-rose-300" : "text-zinc-200"}`, children: [_jsx("span", { className: "w-5 h-5 rounded-full bg-zinc-800 text-xs flex items-center justify-center text-zinc-400", children: number }), label] }), _jsx("div", { className: "space-y-2", children: children })] }));
}
function StockSearchInput({ query, setQuery, selected, results, loading, onSelect, }) {
    const [activeIdx, setActiveIdx] = useState(0);
    useEffect(() => {
        setActiveIdx(0);
    }, [query]);
    if (selected)
        return null;
    const visible = results.slice(0, 10);
    const showDropdown = query.trim() !== "" && (loading || visible.length > 0);
    return (_jsxs("div", { className: "relative", children: [_jsx("input", { type: "text", value: query, onChange: (e) => setQuery(e.target.value), onKeyDown: (e) => {
                    if (visible.length === 0)
                        return;
                    if (e.key === "ArrowDown") {
                        e.preventDefault();
                        setActiveIdx((i) => Math.min(i + 1, visible.length - 1));
                    }
                    else if (e.key === "ArrowUp") {
                        e.preventDefault();
                        setActiveIdx((i) => Math.max(i - 1, 0));
                    }
                    else if (e.key === "Enter") {
                        e.preventDefault();
                        onSelect(visible[activeIdx]);
                    }
                }, placeholder: "\uD83D\uDD0D \uC885\uBAA9\uBA85 \uB610\uB294 \uCF54\uB4DC \uC785\uB825 (\uC608: \uC0BC\uC131\uC804\uC790 / 005930)", className: "w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-zinc-100 focus:outline-none focus:border-emerald-700" }), showDropdown && (_jsxs("div", { className: "absolute z-10 left-0 right-0 mt-1 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl max-h-60 overflow-y-auto", children: [loading && (_jsx("div", { className: "px-3 py-2 text-xs text-zinc-500", children: "\uAC80\uC0C9 \uC911..." })), visible.map((s, i) => (_jsxs("button", { type: "button", onClick: () => onSelect(s), onMouseEnter: () => setActiveIdx(i), className: `w-full text-left px-3 py-2 text-sm flex justify-between ${i === activeIdx ? "bg-zinc-800" : "hover:bg-zinc-800/50"}`, children: [_jsx("span", { children: s.stockName }), _jsx("span", { className: "text-zinc-500", children: s.stockCode })] }, s.stockCode)))] })), _jsx("p", { className: "text-xs text-zinc-500 mt-1", children: "\u2191/\u2193\uB85C \uC774\uB3D9, Enter\uB85C \uC120\uD0DD" })] }));
}
function PriceDisplay({ stock, currentPrice, asOf, loading, onRefresh, onClear, }) {
    return (_jsxs("div", { className: "bg-zinc-950 border border-zinc-800 rounded p-3 flex items-center justify-between", children: [_jsxs("div", { children: [_jsxs("div", { className: "text-sm font-medium", children: [stock.stockName, _jsx("span", { className: "text-xs text-zinc-500 ml-2", children: stock.stockCode })] }), _jsxs("div", { className: "text-xs text-zinc-400 mt-1", children: ["\uD604\uC7AC\uAC00", " ", _jsx("span", { className: "text-zinc-100 font-medium", children: loading ? "..." : `${formatPrice(currentPrice)}원` }), asOf && (_jsxs("span", { className: "ml-2 text-zinc-500", children: ["\uAE30\uC900 ", formatRelative(asOf)] }))] })] }), _jsxs("div", { className: "flex items-center gap-1", children: [_jsx("button", { type: "button", onClick: onRefresh, disabled: loading, className: "p-1.5 text-zinc-500 hover:text-zinc-200 hover:bg-zinc-800 rounded disabled:opacity-40", title: "\uAC00\uACA9 \uAC31\uC2E0", children: "\u21BB" }), _jsx("button", { type: "button", onClick: onClear, className: "p-1.5 text-zinc-500 hover:text-zinc-200 hover:bg-zinc-800 rounded", title: "\uB2E4\uC2DC \uC120\uD0DD", children: "\u00D7" })] })] }));
}
const AmountInput = forwardRef((props, ref) => (_jsxs("div", { className: "relative", children: [_jsx("input", { ref: ref, type: "number", step: 1, min: 0, ...props, className: "w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-zinc-100 focus:outline-none focus:border-emerald-700 disabled:opacity-50 [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none" }), _jsx("span", { className: "absolute right-3 top-1/2 -translate-y-1/2 text-zinc-500 text-sm pointer-events-none", children: "\uC6D0" })] })));
AmountInput.displayName = "AmountInput";
function AmountPreview({ perBuyAmount, totalReserve, estimatedQty, actualBuyEstimate, currentPrice, availableBalance, insufficientBalance, belowOneShare, }) {
    return (_jsxs("div", { className: "bg-zinc-950 border border-zinc-800 rounded p-3 text-sm space-y-1", children: [_jsxs("div", { className: "flex justify-between", children: [_jsx("span", { className: "text-zinc-500", children: "\uC608\uC0C1 \uB9E4\uC218 (1\uD68C)" }), _jsxs("span", { className: "text-zinc-200", children: [formatQty(estimatedQty), " \u00D7 ", formatPrice(currentPrice), " = ", _jsx("span", { className: "font-medium", children: formatKRW(actualBuyEstimate) })] })] }), _jsxs("div", { className: "flex justify-between", children: [_jsx("span", { className: "text-zinc-500", children: "3\uD68C \uCD1D \uC608\uC57D" }), _jsx("span", { className: "text-zinc-200 font-medium", children: formatKRW(totalReserve) })] }), _jsx("div", { className: "pt-1 mt-1 border-t border-zinc-800", children: belowOneShare ? (_jsx(ErrorMsg, { code: "PRICE_BELOW_ONE_SHARE", extra: `현재가 ${formatPrice(currentPrice)}원 이상 필요` })) : insufficientBalance && availableBalance !== undefined ? (_jsx(ErrorMsg, { code: "INSUFFICIENT_BALANCE", extra: `사용 가능 ${formatKRW(availableBalance)}` })) : perBuyAmount < 10_000 ? (_jsx("p", { className: "text-xs text-rose-300", children: "\uCD5C\uC18C 10,000\uC6D0" })) : (_jsx("p", { className: "text-xs text-emerald-400", children: "\u2713 \uC794\uACE0 \uD55C\uB3C4 \uB0B4" })) })] }));
}
function AdvancedSettings({ register, dirtyKeys, onReset, }) {
    return (_jsxs("div", { className: "mt-3 grid grid-cols-1 md:grid-cols-2 gap-3 p-4 bg-zinc-950 border border-zinc-800 rounded", children: [_jsx(NumField, { label: "\uB9E4\uC218 \uAC04\uACA9 (\uBD84)", defaultValue: DEFAULTS.buyIntervalMin, dirty: dirtyKeys.has("buyIntervalMin"), registration: register("buyIntervalMin", {
                    setValueAs: (v) => (v === "" || v == null ? null : Number(v)),
                }), step: 1 }), _jsx(NumField, { label: "\uBD84\uD560 \uB9E4\uB3C4 \uBE44\uC728 (%)", defaultValue: DEFAULTS.splitSellRatio, dirty: dirtyKeys.has("splitSellRatio"), registration: register("splitSellRatio", {
                    setValueAs: (v) => (v === "" || v == null ? null : Number(v)),
                }), step: 1 }), _jsx(NumField, { label: "\uC911\uB3C4 \uC775\uC808 (%)", defaultValue: DEFAULTS.midwayProfitPct, dirty: dirtyKeys.has("midwayProfitPct"), registration: register("midwayProfitPct", {
                    setValueAs: (v) => (v === "" || v == null ? null : Number(v)),
                }), step: 0.1 }), _jsx(NumField, { label: "\uBCF8\uC804 \uB9E4\uB3C4 \uAE30\uC900 (%)", defaultValue: DEFAULTS.breakevenThresholdPct, dirty: dirtyKeys.has("breakevenThresholdPct"), registration: register("breakevenThresholdPct", {
                    setValueAs: (v) => (v === "" || v == null ? null : Number(v)),
                }), step: 0.1 }), _jsx(NumField, { label: "\uC190\uC808 (%)", defaultValue: DEFAULTS.stopLossPct, dirty: dirtyKeys.has("stopLossPct"), registration: register("stopLossPct", {
                    setValueAs: (v) => (v === "" || v == null ? null : Number(v)),
                }), step: 0.1 }), _jsx("div", { className: "md:col-span-2 flex justify-end", children: _jsx("button", { type: "button", onClick: onReset, disabled: dirtyKeys.size === 0, className: "text-xs text-zinc-500 hover:text-zinc-300 disabled:opacity-40", children: "\uAE30\uBCF8\uAC12\uC73C\uB85C \uB9AC\uC14B" }) })] }));
}
function NumField({ label, defaultValue, dirty, registration, step, }) {
    return (_jsxs("label", { className: "block", children: [_jsxs("span", { className: `text-xs flex items-center gap-1 mb-1 ${dirty ? "text-amber-300" : "text-zinc-400"}`, children: [dirty && _jsx("span", { className: "text-amber-400", children: "\u25CF" }), label] }), _jsx("input", { type: "number", step: step, placeholder: String(defaultValue), ...registration, className: "w-full bg-zinc-900 border border-zinc-800 rounded px-2 py-1.5 text-sm text-zinc-100 focus:outline-none focus:border-emerald-700" })] }));
}
// ============================================================
// Sidebar
// ============================================================
function BalancePanel({ balance, loading, error, errorCode, onRetry, insufficient, }) {
    return (_jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-4", children: [_jsx("h3", { className: "text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3", children: "\uC794\uACE0" }), loading ? (_jsxs("div", { className: "space-y-2", children: [_jsx(Skeleton, { className: "h-4 w-full" }), _jsx(Skeleton, { className: "h-4 w-full" }), _jsx(Skeleton, { className: "h-5 w-full" })] })) : error || !balance ? (_jsxs("div", { className: "text-sm text-rose-300 space-y-2", children: [_jsxs("p", { children: ["\uC794\uACE0 \uC870\uD68C \uC2E4\uD328", errorCode && (_jsxs("span", { className: "text-xs text-rose-400 block mt-1", children: ["(", errorCode, ")"] }))] }), _jsx("button", { onClick: onRetry, className: "text-xs text-zinc-300 hover:text-white px-2 py-1 rounded bg-zinc-800 border border-zinc-700", children: "\uB2E4\uC2DC \uC2DC\uB3C4" })] })) : (_jsxs(_Fragment, { children: [_jsx(Row, { label: "\uAC00\uC6A9 \uD604\uAE08", value: formatKRW(balance.cashBalance) }), _jsx(Row, { label: "\uD65C\uC131 \uC608\uC57D", value: `-${formatKRW(balance.reservedAmount)}`, valueClass: "text-zinc-400" }), _jsx("div", { className: "border-t border-zinc-800 mt-2 pt-2", children: _jsx(Row, { label: "\uC0AC\uC6A9 \uAC00\uB2A5", value: _jsx(FlashOnChange, { value: balance.availableBalance, children: formatKRW(balance.availableBalance) }), valueClass: insufficient
                                ? "text-rose-300 font-semibold"
                                : "text-emerald-300 font-semibold" }) })] }))] }));
}
function SystemPanel({ status, loading, }) {
    if (loading || !status) {
        return (_jsx("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-4 space-y-2", children: Array.from({ length: 4 }).map((_, i) => (_jsx(Skeleton, { className: "h-4 w-full" }, i))) }));
    }
    const conditions = [
        { label: "거래시간 09:00–15:30", ok: status.tradingHoursOpen },
        { label: "휴장 아님", ok: !status.isHoliday },
        { label: "컷오프 전 (15:20)", ok: !status.cutoffPassed },
        {
            label: `시세 모드 ${status.marketMode}`,
            ok: status.marketMode === "WS",
        },
    ];
    return (_jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-4", children: [_jsx("h3", { className: "text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3", children: "\uC2DC\uC7A5 \uC0C1\uD0DC" }), _jsx("div", { className: "space-y-1.5", children: conditions.map((c) => (_jsxs("div", { className: "flex items-center gap-2 text-sm", children: [_jsx("span", { className: `w-1.5 h-1.5 rounded-full ${c.ok ? "bg-emerald-400" : "bg-rose-400"}` }), _jsx("span", { className: c.ok ? "text-zinc-300" : "text-rose-300", children: c.label })] }, c.label))) })] }));
}
function ActiveCommandsPreview({ commands, highlightStock, loading, }) {
    return (_jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-4", children: [_jsxs("h3", { className: "text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3", children: ["\uD65C\uC131 \uBA85\uB839 (", loading ? "..." : commands.length, ")"] }), loading ? (_jsxs("div", { className: "space-y-2", children: [_jsx(Skeleton, { className: "h-6 w-full" }), _jsx(Skeleton, { className: "h-6 w-full" })] })) : commands.length === 0 ? (_jsx("p", { className: "text-xs text-zinc-600", children: "\uC5C6\uC74C" })) : (_jsx("div", { className: "space-y-2", children: commands.map((c) => {
                    const isHighlight = highlightStock === c.stockCode;
                    return (_jsxs(Link, { to: "/monitoring", className: `flex items-center justify-between gap-2 p-2 rounded text-xs hover:bg-zinc-800 ${isHighlight ? "bg-rose-950/40 border border-rose-800/60" : ""}`, children: [_jsxs("span", { className: "flex items-center gap-2 min-w-0", children: [_jsx(StatusPill, { status: c.status }), _jsx("span", { className: "truncate", children: c.stockName })] }), _jsx(ProfitText, { value: c.profitRate, format: formatPct })] }, c.cycleId));
                }) })), _jsx("p", { className: "text-xs text-zinc-500 mt-3", children: "\uAC19\uC740 \uC885\uBAA9 \uC911\uBCF5 \uBA85\uB839\uC740 \uAC70\uBD80\uB429\uB2C8\uB2E4" })] }));
}
// ============================================================
// Helpers
// ============================================================
function Row({ label, value, valueClass, }) {
    return (_jsxs("div", { className: "flex justify-between items-baseline py-1.5 text-sm", children: [_jsx("span", { className: "text-zinc-500", children: label }), _jsx("span", { className: valueClass ?? "text-zinc-200", children: value })] }));
}
function ErrorMsg({ code, extra }) {
    return (_jsxs("p", { className: "text-xs text-rose-300", children: ["\u00B7 ", errorMessage(code), extra && _jsxs("span", { className: "ml-1 text-rose-400", children: ["(", extra, ")"] })] }));
}
