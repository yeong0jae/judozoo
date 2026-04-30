import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useMemo, useState } from "react";
import { mockBalance, mockStockSearch, mockSystemStatus } from "../mocks/data";
import { formatKrw, formatPrice, formatQty } from "../lib/format";
export default function CommandPage() {
    const [selectedStock, setSelectedStock] = useState(mockStockSearch[0]);
    const [perBuyAmount, setPerBuyAmount] = useState(1_000_000);
    const [advancedOpen, setAdvancedOpen] = useState(false);
    const [buyIntervalMin, setBuyIntervalMin] = useState(3);
    const [splitSellRatio, setSplitSellRatio] = useState(20);
    const [midwayProfitPct, setMidwayProfitPct] = useState(3);
    const [breakevenPct, setBreakevenPct] = useState(2);
    const [stopLossPct, setStopLossPct] = useState(-2);
    const estimatedQty = useMemo(() => Math.floor(perBuyAmount / selectedStock.currentPrice), [perBuyAmount, selectedStock]);
    const totalReserve = perBuyAmount * 3;
    const blockedReason = mockSystemStatus.isHoliday
        ? "휴장일 — 명령 접수 불가"
        : !mockSystemStatus.tradingHoursOpen
            ? "정규장 시간 외 (09:00~15:30)"
            : mockSystemStatus.cutoffPassed
                ? "신규 명령 컷오프 도달 (15:20 - 추가 매수 간격 × 2)"
                : mockSystemStatus.tokenStatus !== "OK"
                    ? "KIS 토큰 실패 — 일시적으로 명령 차단"
                    : null;
    const lacksFunds = totalReserve > mockBalance.availableBalance;
    const belowOneShare = perBuyAmount < selectedStock.currentPrice;
    const belowMin = perBuyAmount < 10_000;
    const submitDisabled = !!blockedReason || lacksFunds || belowOneShare || belowMin;
    return (_jsxs("div", { className: "grid grid-cols-1 lg:grid-cols-3 gap-6", children: [_jsxs("section", { className: "lg:col-span-2 bg-zinc-900 border border-zinc-800 rounded-lg p-6", children: [_jsx("h2", { className: "text-lg font-semibold mb-4", children: "\uB9E4\uB9E4 \uBA85\uB839 \uC785\uB825" }), _jsx(Field, { label: "\uC885\uBAA9 \uC120\uD0DD", children: _jsx("select", { value: selectedStock.stockCode, onChange: (e) => {
                                const s = mockStockSearch.find((x) => x.stockCode === e.target.value);
                                if (s)
                                    setSelectedStock(s);
                            }, className: inputCls, children: mockStockSearch.map((s) => (_jsxs("option", { value: s.stockCode, children: [s.stockName, " (", s.stockCode, ")"] }, s.stockCode))) }) }), _jsxs(Field, { label: "1\uD68C \uB9E4\uC218 \uAE08\uC561 (\uC6D0)", children: [_jsx("input", { type: "number", value: perBuyAmount, onChange: (e) => setPerBuyAmount(Number(e.target.value)), className: inputCls, step: 10_000, min: 10_000 }), _jsxs("p", { className: "text-xs text-zinc-500 mt-1", children: ["\uD5C8\uC6A9 \uBC94\uC704: 10,000\uC6D0 \uC774\uC0C1 AND 1\uC8FC \uAC00\uACA9(", formatPrice(selectedStock.currentPrice), "\uC6D0) \uC774\uC0C1"] })] }), _jsxs("button", { type: "button", onClick: () => setAdvancedOpen((v) => !v), className: "text-sm text-zinc-400 hover:text-zinc-200 mt-2", children: [advancedOpen ? "▾" : "▸", " \uACE0\uAE09 \uC124\uC815 (\uB8F0 \uD30C\uB77C\uBBF8\uD130)"] }), advancedOpen && (_jsxs("div", { className: "mt-3 grid grid-cols-2 gap-4 p-4 bg-zinc-950 border border-zinc-800 rounded", children: [_jsx(Field, { label: "\uCD94\uAC00 \uB9E4\uC218 \uAC04\uACA9 (\uBD84)", hint: "1~30", children: _jsx("input", { type: "number", value: buyIntervalMin, onChange: (e) => setBuyIntervalMin(Number(e.target.value)), className: inputCls, min: 1, max: 30 }) }), _jsx(Field, { label: "\uBD84\uD560 \uB9E4\uB3C4 \uBE44\uC728 (%)", hint: "10~30", children: _jsx("input", { type: "number", value: splitSellRatio, onChange: (e) => setSplitSellRatio(Number(e.target.value)), className: inputCls, min: 10, max: 30 }) }), _jsx(Field, { label: "\uC911\uB3C4 \uC775\uC808 \uAE30\uC900 \uC218\uC775\uB960 (%)", hint: "0.1~10", children: _jsx("input", { type: "number", value: midwayProfitPct, onChange: (e) => setMidwayProfitPct(Number(e.target.value)), className: inputCls, step: 0.1 }) }), _jsx(Field, { label: "\uBCF8\uC804 \uB9E4\uB3C4 \uAE30\uC900 (%)", hint: "0.1~10", children: _jsx("input", { type: "number", value: breakevenPct, onChange: (e) => setBreakevenPct(Number(e.target.value)), className: inputCls, step: 0.1 }) }), _jsx(Field, { label: "\uC190\uC808 \uAE30\uC900 \uC190\uC2E4\uB960 (%)", hint: "-0.1 ~ -10", children: _jsx("input", { type: "number", value: stopLossPct, onChange: (e) => setStopLossPct(Number(e.target.value)), className: inputCls, step: 0.1 }) })] })), _jsx("button", { disabled: submitDisabled, className: "mt-6 w-full bg-emerald-700 hover:bg-emerald-600 disabled:bg-zinc-700 disabled:text-zinc-500 text-white px-4 py-3 rounded-md font-medium transition-colors", children: "\uB9E4\uC218 \uBA85\uB839 \uC804\uC1A1" }), (blockedReason || lacksFunds || belowOneShare || belowMin) && (_jsxs("div", { className: "mt-3 text-sm text-rose-400 space-y-1", children: [blockedReason && _jsxs("p", { children: ["\u00B7 ", blockedReason] }), lacksFunds && (_jsxs("p", { children: ["\u00B7 \uC794\uACE0 \uBD80\uC871: \uD544\uC694 ", formatKrw(totalReserve), " ", ">", " \uAC00\uC6A9 ", formatKrw(mockBalance.availableBalance)] })), belowOneShare && _jsx("p", { children: "\u00B7 1\uC8FC \uAC00\uACA9 \uBBF8\uB9CC" }), belowMin && _jsx("p", { children: "\u00B7 \uCD5C\uC18C 10,000\uC6D0 \uBBF8\uB9CC" })] }))] }), _jsxs("aside", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-6 h-fit sticky top-6", children: [_jsx("h3", { className: "text-sm font-semibold text-zinc-400 mb-4", children: "\uAC80\uC99D \uCEE8\uD14D\uC2A4\uD2B8" }), _jsx(Row, { label: "\uC0AC\uC6A9 \uAC00\uB2A5 \uC794\uACE0", value: formatKrw(mockBalance.availableBalance), hint: `예약 차감: ${formatKrw(mockBalance.reservedAmount)}` }), _jsx(Row, { label: "\uC120\uD0DD \uC885\uBAA9", value: `${selectedStock.stockName} (${selectedStock.stockCode})` }), _jsx(Row, { label: "\uD604\uC7AC\uAC00", value: `${formatPrice(selectedStock.currentPrice)}원` }), _jsx(Row, { label: "\uC608\uC0C1 \uB9E4\uC218 \uC218\uB7C9", value: formatQty(estimatedQty), hint: "1\uD68C\uBD84, \uC18C\uC218\uC810 \uC808\uC0AC" }), _jsx(Row, { label: "\uC608\uC0C1 \uCD1D \uB9E4\uC218 \uAE08\uC561", value: formatKrw(totalReserve), hint: "1\uD68C\uBD84 \u00D7 3" }), _jsx("div", { className: "mt-4 pt-4 border-t border-zinc-800 text-xs text-zinc-500", children: "\uC794\uACE0\uB294 \uB2E4\uB978 \uBA85\uB839 \uC0DD\uC131/\uC885\uB8CC \uC2DC \uC790\uB3D9 \uAC31\uC2E0\uB429\uB2C8\uB2E4 (BALANCE_INVALIDATED \uD478\uC2DC)." })] })] }));
}
const inputCls = "w-full bg-zinc-950 border border-zinc-800 rounded px-3 py-2 text-zinc-100 focus:outline-none focus:border-emerald-700";
function Field({ label, hint, children, }) {
    return (_jsxs("div", { className: "mb-4", children: [_jsxs("label", { className: "block text-sm font-medium text-zinc-300 mb-1", children: [label, hint && _jsxs("span", { className: "text-xs text-zinc-500 ml-2", children: ["(", hint, ")"] })] }), children] }));
}
function Row({ label, value, hint, }) {
    return (_jsxs("div", { className: "flex justify-between items-baseline py-2 border-b border-zinc-800 last:border-0", children: [_jsx("span", { className: "text-sm text-zinc-400", children: label }), _jsxs("div", { className: "text-right", children: [_jsx("div", { className: "text-sm font-medium text-zinc-100", children: value }), hint && _jsx("div", { className: "text-xs text-zinc-500", children: hint })] })] }));
}
