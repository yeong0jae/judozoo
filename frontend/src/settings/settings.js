import { jsx as _jsx } from "react/jsx-runtime";
// 사용자 설정 — localStorage 백업.
import { createContext, useCallback, useContext, useState, } from "react";
const STORAGE_KEY = "at.settings";
const DEFAULTS = {
    osNotificationsEnabled: false,
    soundEnabled: false,
    emphasizeUnclosed: true,
};
const Ctx = createContext(null);
function loadSettings() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (!raw)
            return DEFAULTS;
        return { ...DEFAULTS, ...JSON.parse(raw) };
    }
    catch {
        return DEFAULTS;
    }
}
export function SettingsProvider({ children }) {
    const [settings, setSettings] = useState(() => loadSettings());
    const set = useCallback((key, value) => {
        setSettings((prev) => {
            const next = { ...prev, [key]: value };
            try {
                localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
            }
            catch {
                // ignore
            }
            return next;
        });
    }, []);
    return (_jsx(Ctx.Provider, { value: { ...settings, set }, children: children }));
}
export function useSettings() {
    const v = useContext(Ctx);
    if (!v)
        throw new Error("useSettings must be used inside <SettingsProvider>");
    return v;
}
