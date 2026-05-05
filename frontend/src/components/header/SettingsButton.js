import { jsx as _jsx, Fragment as _Fragment, jsxs as _jsxs } from "react/jsx-runtime";
import { useState } from "react";
import { useSettings } from "../../settings/settings";
export default function SettingsButton() {
    const [open, setOpen] = useState(false);
    const settings = useSettings();
    return (_jsxs(_Fragment, { children: [_jsx("button", { onClick: () => setOpen(true), className: "flex items-center justify-center w-8 h-8 rounded hover:bg-zinc-800 text-zinc-400 hover:text-zinc-200 transition-colors", "aria-label": "\uC124\uC815", children: "\u2699" }), open && (_jsx(SettingsModal, { onClose: () => setOpen(false), settings: settings }))] }));
}
function SettingsModal({ onClose, settings, }) {
    return (_jsx("div", { className: "fixed inset-0 bg-black/70 flex items-center justify-center z-50", onClick: onClose, children: _jsxs("div", { className: "bg-zinc-900 border border-zinc-800 rounded-lg p-6 max-w-md w-full", onClick: (e) => e.stopPropagation(), children: [_jsxs("div", { className: "flex items-center justify-between mb-4", children: [_jsx("h3", { className: "text-lg font-semibold", children: "\uC124\uC815" }), _jsx("button", { onClick: onClose, className: "text-zinc-500 hover:text-zinc-200", children: "\u00D7" })] }), _jsxs("div", { className: "space-y-3", children: [_jsx(Toggle, { label: "OS \uB370\uC2A4\uD06C\uD1B1 \uC54C\uB9BC", hint: "\uBE0C\uB77C\uC6B0\uC800\uAC00 \uBC31\uADF8\uB77C\uC6B4\uB4DC\uC5EC\uB3C4 OS \uC54C\uB9BC\uC73C\uB85C \uC885\uB8CC\uB97C \uC778\uC9C0", value: settings.osNotificationsEnabled, onChange: (v) => handleToggle("osNotificationsEnabled", v, settings.set) }), _jsx(Toggle, { label: "\uC0AC\uC6B4\uB4DC \uC54C\uB9BC", hint: "\uC0AC\uC774\uD074 \uC885\uB8CC \uC2DC \uC18C\uB9AC", value: settings.soundEnabled, onChange: (v) => settings.set("soundEnabled", v) }), _jsx(Toggle, { label: "UNCLOSED / NO_FILL \uAC15\uC870", hint: "\uC6B4\uC601 \uC810\uAC80\uC774 \uD544\uC694\uD55C \uC885\uB8CC\uB97C \uBE68\uAC15\uC73C\uB85C \uAC15\uC870", value: settings.emphasizeUnclosed, onChange: (v) => settings.set("emphasizeUnclosed", v) })] })] }) }));
}
async function handleToggle(key, value, set) {
    if (key === "osNotificationsEnabled" && value) {
        if (!("Notification" in window)) {
            alert("이 브라우저는 OS 알림을 지원하지 않습니다");
            return;
        }
        const result = await Notification.requestPermission();
        if (result !== "granted") {
            alert("OS 알림 권한이 거부되었습니다");
            return;
        }
    }
    set(key, value);
}
function Toggle({ label, hint, value, onChange, }) {
    return (_jsxs("label", { className: "flex items-start justify-between gap-4 cursor-pointer p-3 rounded hover:bg-zinc-800/50 -mx-3", children: [_jsxs("div", { className: "flex-1", children: [_jsx("div", { className: "text-sm", children: label }), hint && _jsx("div", { className: "text-xs text-zinc-500 mt-0.5", children: hint })] }), _jsx("button", { type: "button", role: "switch", "aria-checked": value, onClick: () => onChange(!value), className: `shrink-0 mt-0.5 w-10 h-6 rounded-full p-0.5 transition-colors ${value ? "bg-emerald-600" : "bg-zinc-700"}`, children: _jsx("span", { className: `block w-5 h-5 bg-white rounded-full transition-transform ${value ? "translate-x-4" : ""}` }) })] }));
}
