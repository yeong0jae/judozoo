// 사용자 설정 — localStorage 백업.

import {
  createContext,
  useCallback,
  useContext,
  useState,
  type ReactNode,
} from "react";

export interface Settings {
  osNotificationsEnabled: boolean;
  soundEnabled: boolean;
  emphasizeUnclosed: boolean;
}

export type SettingsKey = keyof Settings;

interface SettingsValue extends Settings {
  set: (key: SettingsKey, value: boolean) => void;
}

const STORAGE_KEY = "at.settings";

const DEFAULTS: Settings = {
  osNotificationsEnabled: false,
  soundEnabled: false,
  emphasizeUnclosed: true,
};

const Ctx = createContext<SettingsValue | null>(null);

function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULTS;
    return { ...DEFAULTS, ...(JSON.parse(raw) as Partial<Settings>) };
  } catch {
    return DEFAULTS;
  }
}

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettings] = useState<Settings>(() => loadSettings());

  const set = useCallback((key: SettingsKey, value: boolean) => {
    setSettings((prev) => {
      const next = { ...prev, [key]: value };
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      } catch {
        // ignore
      }
      return next;
    });
  }, []);

  return (
    <Ctx.Provider value={{ ...settings, set }}>{children}</Ctx.Provider>
  );
}

export function useSettings(): SettingsValue {
  const v = useContext(Ctx);
  if (!v) throw new Error("useSettings must be used inside <SettingsProvider>");
  return v;
}
