import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { mockActiveCommands, mockBalance, mockStockPrices, mockStockSearch, mockSystemStatus, } from "../mocks/data";
import { formatKRW, formatPrice, formatQty, formatRelative, } from "../lib/format";
import { errorMessage } from "../lib/errorMessages";
import StatusPill from "../components/common/StatusPill";
import ProfitText from "../components/common/ProfitText";
import FlashOnChange from "../components/common/FlashOnChange";
import { useToast } from "../components/toast/Toast";
import { formatPct } from "../lib/format";
const DEFAULTS = {
    buyIntervalMin: 3,
    splitSellRatio: 20,
    midwayProfitPct: 3,
    breakevenThresholdPct: 2,
    stopLossPct: -2,
};
export default function CommandPage() {
    const toast = useToast();
    const [query, setQuery] = useState("");
    const [selectedStock, setSelectedStock] = useState(null);
    const [priceRefreshAt, setPriceRefreshAt] = useState(Date.now());
    const [perBuyAmount, setPerBuyAmount] = useState(1_000_000);
    const [advancedOpen, setAdvancedOpen] = useState(false);
    const [advanced, setAdvanced] = useState(DEFAULTS);
    const [serverError, setServerError] = useState(null);
    const status = mockSystemStatus;
    const balance = mockBalance;
    const block = deriveBlock(status);
    const price = selectedStock
        ? mockStockPrices[selectedStock.stockCode] ?? null
        : null;
    const currentPrice = price?.currentPrice ?? 0;
    const estimatedQty = currentPrice > 0 ? Math.floor(perBuyAmount / currentPrice) : 0;
    const totalReserve = perBuyAmount * 3;
    const totalActualBuyEstimate = currentPrice * estimatedQty;
    const insufficientBalance = totalReserve > balance.availableBalance;
    const belowOneShare = currentPrice > 0 && perBuyAmount < currentPrice;
    const duplicateActive = selectedStock !== null &&
        mockActiveCommands.some((c) => c.stockCode === selectedStock.stockCode);
    const advancedDirty = useMemo(() => Object.keys(DEFAULTS).filter((k) => advanced[k] !== DEFAULTS[k]), [advanced]);
    const submitDisabled = !!block ||
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
        if (!selectedStock)
            return;
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
    return (_jsxs("div", { className: "space-y-6", children: [block && _jsx(BlockBanner, { reason: block }), _jsxs("div", { className: "grid grid-cols-1 lg:grid-cols-3 gap-6", children: [_jsxs("section", { className: "lg:col-span-2 bg-zinc-900 border border-zinc-800 rounded-lg p-6 space-y-6", children: [_jsx("h2", { className: "text-lg font-semibold", children: "\uC0C8 \uB9E4\uB9E4 \uBA85\uB839" }), _jsxs(Field, { number: 1, label: "\uC885\uBAA9", error: serverError === "STOCK_NOT_FOUND" || serverError === "DUPLICATE_COMMAND", children: [_jsx(StockSearchInput, { query: query, setQuery: setQuery, selected: selectedStock, onSelect: (s) => {
                                            setSelectedStock(s);
                                            setQuery("");
                                            setServerError(null);
                                        } }), selectedStock && (_jsx(PriceDisplay, { stock: selectedStock, currentPrice: currentPrice, asOf: price?.asOf, onRefresh: () => setPriceRefreshAt(Date.now()), refreshKey: priceRefreshAt, onClear: () => setSelectedStock(null) })), duplicateActive && (_jsx(ErrorMsg, { code: "DUPLICATE_COMMAND" })), serverError === "STOCK_NOT_FOUND" && (_jsx(ErrorMsg, { code: "STOCK_NOT_FOUND" }))] }), _jsxs(Field, { number: 2, label: "1\uD68C \uB9E4\uC218\uAE08\uC561", disabled: !selectedStock || !!block, error: insufficientBalance ||
                                    belowOneShare ||
                                    serverError === "PRICE_BELOW_ONE_SHARE" ||
                                    serverError === "INSUFFICIENT_BALANCE", children: [_jsx(AmountInput, { value: perBuyAmount, onChange: setPerBuyAmount, disabled: !selectedStock || !!block }), selectedStock && (_jsx(AmountPreview, { perBuyAmount: perBuyAmount, totalReserve: totalReserve, estimatedQty: estimatedQty, actualBuyEstimate: totalActualBuyEstimate, currentPrice: currentPrice, availableBalance: balance.availableBalance, insufficientBalance: insufficientBalance, belowOneShare: belowOneShare }))] }), _jsxs(Field, { number: 3, label: "\uACE0\uAE09 \uC124\uC815", children: [_jsxs("button", { type: "button", onClick: () => setAdvancedOpen((v) => !v), className: "text-sm text-zinc-400 hover:text-zinc-200 flex items-center gap-1", children: [_jsx("span", { children: advancedOpen ? "▾" : "▸" }), _jsx("span", { children: advancedDirty.length === 0
                                                    ? "기본값 사용 중"
                                                    : `${advancedDirty.length}개 항목 변경됨` })] }), advancedOpen && (_jsx(AdvancedSettings, { value: advanced, onChange: setAdvanced, dirtyKeys: new Set(advancedDirty) }))] }), _jsx("button", { disabled: submitDisabled, onClick: onSubmit, className: "w-full bg-emerald-700 hover:bg-emerald-600 disabled:bg-zinc-800 disabled:text-zinc-600 text-white px-4 py-3 rounded-md font-medium transition-colors", children: "\uB9E4\uB9E4 \uC2DC\uC791" }), serverError &&
                                !["STOCK_NOT_FOUND", "DUPLICATE_COMMAND"].includes(serverError) && (_jsx(ErrorMsg, { code: serverError }))] }), _jsxs("aside", { className: "space-y-6", children: [_jsx(BalancePanel, { balance: balance, insufficient: insufficientBalance }), _jsx(SystemPanel, { status: status }), _jsx(ActiveCommandsPreview, { commands: mockActiveCommands, highlightStock: selectedStock?.stockCode })] })] })] }));
}
// ============================================================
// Block banner
// ============================================================
function deriveBlock(s) {
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
function StockSearchInput({ query, setQuery, selected, onSelect, }) {
    const [activeIdx, setActiveIdx] = useState(0);
    const results = useMemo(() => {
        if (selected || query.trim() === "")
            return [];
        const q = query.trim().toLowerCase();
        return mockStockSearch
            .filter((s) => s.stockName.toLowerCase().includes(q) || s.stockCode.includes(q))
            .slice(0, 10);
    }, [query, selected]);
    useEffect(() => {
        setActiveIdx(0);
    }, [query]);
    if (selected)
        return null;
    return (_jsxs("div", { className: "relative", children: [_jsx("input", { type: "text", value: query, onChange: (e) => setQuery(e.target.value), onKeyDown: (e) => {
                    if (results.length === 0)
                        return;
                    if (e.key === "ArrowDown") {
                        e.preventDefault();
                        setActiveIdx((i) => Math.min(i + 1, results.length - 1));
                    }
                    else if (e.key === "ArrowUp") {
                        e.preventDefault();
                        setActiveIdx((i) => Math.max(i - 1, 0));
                    }
                    else if (e.key === "Enter") {
                        e.preventDefault();
                        onSelect(results[activeIdx]);
                    }
                }, placeholder: "\uD83D\uDD0D \uC885\uBAA9\uBA85 \uB610\uB294 \uCF54\uB4DC \uC785\uB825 (\uC608: \uC0BC\uC131\uC804\uC790 / 005930)", className: "w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-zinc-100 focus:outline-none focus:border-emerald-700" }), results.length > 0 && (_jsx("div", { className: "absolute z-10 left-0 right-0 mt-1 bg-zinc-900 border border-zinc-700 rounded-lg shadow-xl max-h-60 overflow-y-auto", children: results.map((s, i) => (_jsxs("button", { onClick: () => onSelect(s), onMouseEnter: () => setActiveIdx(i), className: `w-full text-left px-3 py-2 text-sm flex justify-between ${i === activeIdx ? "bg-zinc-800" : "hover:bg-zinc-800/50"}`, children: [_jsx("span", { children: s.stockName }), _jsx("span", { className: "text-zinc-500", children: s.stockCode })] }, s.stockCode))) })), _jsx("p", { className: "text-xs text-zinc-500 mt-1", children: "\u2191/\u2193\uB85C \uC774\uB3D9, Enter\uB85C \uC120\uD0DD" })] }));
}
function PriceDisplay({ stock, currentPrice, asOf, onRefresh, refreshKey, onClear, }) {
    return (_jsxs("div", { className: "bg-zinc-950 border border-zinc-800 rounded p-3 flex items-center justify-between", children: [_jsxs("div", { children: [_jsxs("div", { className: "text-sm font-medium", children: [stock.stockName, _jsx("span", { className: "text-xs text-zinc-500 ml-2", children: stock.stockCode })] }), _jsxs("div", { className: "text-xs text-zinc-400 mt-1", children: ["\uD604\uC7AC\uAC00", " ", _jsxs("span", { className: "text-zinc-100 font-medium", children: [formatPrice(currentPrice), "\uC6D0"] }), asOf && (_jsxs("span", { className: "ml-2 text-zinc-500", children: ["\uAE30\uC900 ", formatRelative(asOf)] }))] })] }), _jsxs("div", { className: "flex items-center gap-1", children: [_jsx("button", { onClick: onRefresh, className: "p-1.5 text-zinc-500 hover:text-zinc-200 hover:bg-zinc-800 rounded", title: "\uAC00\uACA9 \uAC31\uC2E0", children: "\u21BB" }), _jsx("button", { onClick: onClear, className: "p-1.5 text-zinc-500 hover:text-zinc-200 hover:bg-zinc-800 rounded", title: "\uB2E4\uC2DC \uC120\uD0DD", children: "\u00D7" })] })] }, refreshKey));
}
function AmountInput({ value, onChange, disabled, }) {
    const [text, setText] = useState(formatComma(value));
    useEffect(() => {
        setText(formatComma(value));
    }, [value]);
    return (_jsxs("div", { className: "relative", children: [_jsx("input", { type: "text", inputMode: "numeric", value: text, onChange: (e) => {
                    const raw = e.target.value.replace(/[^0-9]/g, "");
                    setText(raw === "" ? "" : formatComma(Number(raw)));
                    onChange(raw === "" ? 0 : Number(raw));
                }, disabled: disabled, className: "w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-zinc-100 focus:outline-none focus:border-emerald-700 disabled:opacity-50" }), _jsx("span", { className: "absolute right-3 top-1/2 -translate-y-1/2 text-zinc-500 text-sm", children: "\uC6D0" })] }));
}
function formatComma(v) {
    return new Intl.NumberFormat("en-US").format(v);
}
function AmountPreview({ perBuyAmount, totalReserve, estimatedQty, actualBuyEstimate, currentPrice, availableBalance, insufficientBalance, belowOneShare, }) {
    return (_jsxs("div", { className: "bg-zinc-950 border border-zinc-800 rounded p-3 text-sm space-y-1", children: [_jsxs("div", { className: "flex justify-between", children: [_jsx("span", { className: "text-zinc-500", children: "\uC608\uC0C1 \uB9E4\uC218 (1\uD68C)" }), _jsxs("span", { className: "text-zinc-200", children: [formatQty(estimatedQty), " \u00D7 ", formatPrice(currentPrice), " = ", _jsx("span", { className: "font-medium", children: formatKRW(actualBuyEstimate) })] })] }), _jsxs("div", { className: "flex justify-between", children: [_jsx("span", { className: "text-zinc-500", children: "3\uD68C \uCD1D \uC608\uC57D" }), _jsx("span", { className: "text-zinc-200 font-medium", children: formatKRW(totalReserve) })] }), _jsx("div", { className: "pt-1 mt-1 border-t border-zinc-800", children: belowOneShare ? (_jsx(ErrorMsg, { code: "PRICE_BELOW_ONE_SHARE", extra: `현재가 ${formatPrice(currentPrice)}원 이상 필요` })) : insufficientBalance ? (_jsx(ErrorMsg, { code: "INSUFFICIENT_BALANCE", extra: `사용 가능 ${formatKRW(availableBalance)}` })) : perBuyAmount < 10_000 ? (_jsx("p", { className: "text-xs text-rose-300", children: "\uCD5C\uC18C 10,000\uC6D0" })) : (_jsx("p", { className: "text-xs text-emerald-400", children: "\u2713 \uC794\uACE0 \uD55C\uB3C4 \uB0B4" })) })] }));
}
function AdvancedSettings({ value, onChange, dirtyKeys, }) {
    const reset = () => onChange(DEFAULTS);
    return (_jsxs("div", { className: "mt-3 grid grid-cols-1 md:grid-cols-2 gap-3 p-4 bg-zinc-950 border border-zinc-800 rounded", children: [_jsx(NumField, { label: "\uB9E4\uC218 \uAC04\uACA9 (\uBD84)", defaultValue: DEFAULTS.buyIntervalMin, value: value.buyIntervalMin, dirty: dirtyKeys.has("buyIntervalMin"), onChange: (v) => onChange({ ...value, buyIntervalMin: v }), step: 1 }), _jsx(NumField, { label: "\uBD84\uD560 \uB9E4\uB3C4 \uBE44\uC728 (%)", defaultValue: DEFAULTS.splitSellRatio, value: value.splitSellRatio, dirty: dirtyKeys.has("splitSellRatio"), onChange: (v) => onChange({ ...value, splitSellRatio: v }), step: 1 }), _jsx(NumField, { label: "\uC911\uB3C4 \uC775\uC808 (%)", defaultValue: DEFAULTS.midwayProfitPct, value: value.midwayProfitPct, dirty: dirtyKeys.has("midwayProfitPct"), onChange: (v) => onChange({ ...value, midwayProfitPct: v }), step: 0.1 }), _jsx(NumField, { label: "\uBCF8\uC804 \uB9E4\uB3C4 \uAE30\uC900 (%)", defaultValue: DEFAULTS.breakevenThresholdPct, value: value.breakevenThresholdPct, dirty: dirtyKeys.has("breakevenThresholdPct"), onChange: (v) => onChange({ ...value, breakevenThresholdPct: v }), step: 0.1 }), _jsx(NumField, { label: "\uC190\uC808 (%)", defaultValue: DEFAULTS.stopLossPct, value: value.stopLossPct, dirty: dirtyKeys.has("stopLossPct"), onChange: (v) => onChange({ ...value, stopLossPct: v }), step: 0.1 }), _jsx("div", { className: "md:col-span-2 flex justify-end", children: _jsx("button", { onClick: reset, disabled: dirtyKeys.size === 0, className: "text-xs text-zinc-500 hover:text-zinc-300 disabled:opacity-40", children: "\uAE30\uBCF8\uAC12\uC73C\uB85C \uB9AC\uC14B" }) })] }));
}
function NumField({ label, value, defaultValue, dirty, onChange, step, }) {
    return (_jsxs("label", { className: "block", children: [_jsxs("span", { className: `text-xs flex items-center gap-1 mb-1 ${dirty ? "text-amber-300" : "text-zinc-400"}`, children: [dirty && _jsx("span", { className: "text-amber-400", children: "\u25CF" }), label] }), _jsx("input", { type: "number", value: value, step: step, onChange: (e) => onChange(Number(e.target.value)), placeholder: String(defaultValue), className: "w-full bg-zinc-900 border border-zinc-800 rounded px-2 py-1.5 text-sm text-zinc-100 focus:outline-none focus:border-emerald-700" })] }));
}
// ============================================================
// Sidebar
// ============================================================
function BalancePanel({ balance, insufficient, }) {
    return (_jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-4", children: [_jsx("h3", { className: "text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3", children: "\uC794\uACE0" }), _jsx(Row, { label: "\uAC00\uC6A9 \uD604\uAE08", value: formatKRW(balance.cashBalance) }), _jsx(Row, { label: "\uD65C\uC131 \uC608\uC57D", value: `-${formatKRW(balance.reservedAmount)}`, valueClass: "text-zinc-400" }), _jsx("div", { className: "border-t border-zinc-800 mt-2 pt-2", children: _jsx(Row, { label: "\uC0AC\uC6A9 \uAC00\uB2A5", value: _jsx(FlashOnChange, { value: balance.availableBalance, children: formatKRW(balance.availableBalance) }), valueClass: insufficient
                        ? "text-rose-300 font-semibold"
                        : "text-emerald-300 font-semibold" }) }), balance.reservedAmount > 0 && (_jsxs("p", { className: "text-xs text-zinc-500 mt-2", children: ["\uD65C\uC131 \uBA85\uB839 ", mockActiveCommands.length, "\uAC1C\uB85C \uC608\uC57D\uB428"] }))] }));
}
function SystemPanel({ status }) {
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
    return (_jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-4", children: [_jsx("h3", { className: "text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3", children: "\uC2DC\uC2A4\uD15C" }), _jsx("div", { className: "space-y-1.5", children: conditions.map((c) => (_jsxs("div", { className: "flex items-center gap-2 text-sm", children: [_jsx("span", { className: `w-1.5 h-1.5 rounded-full ${c.ok ? "bg-emerald-400" : "bg-rose-400"}` }), _jsx("span", { className: c.ok ? "text-zinc-300" : "text-rose-300", children: c.label })] }, c.label))) })] }));
}
function ActiveCommandsPreview({ commands, highlightStock, }) {
    return (_jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-4", children: [_jsxs("h3", { className: "text-xs font-semibold uppercase tracking-wider text-zinc-500 mb-3", children: ["\uD65C\uC131 \uBA85\uB839 (", commands.length, ")"] }), commands.length === 0 ? (_jsx("p", { className: "text-xs text-zinc-600", children: "\uC5C6\uC74C" })) : (_jsx("div", { className: "space-y-2", children: commands.map((c) => {
                    const isHighlight = highlightStock === c.stockCode;
                    return (_jsxs(Link, { to: "/monitoring", className: `flex items-center justify-between gap-2 p-2 rounded text-xs hover:bg-zinc-800 ${isHighlight ? "bg-rose-950/40 border border-rose-800/60" : ""}`, children: [_jsxs("span", { className: "flex items-center gap-2 min-w-0", children: [_jsx(StatusPill, { status: c.status }), _jsx("span", { className: "truncate", children: c.stockName })] }), _jsx(ProfitText, { value: c.profitRate, format: formatPct })] }, c.commandId));
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
