// 테마 — 라이트/다크/시스템 3-state. <html>에 .dark 클래스를 토글해 적용.
// 저장된 선택이 없으면 다크 — 장중에 오래 보는 화면이라 기본을 어둡게 둔다.

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";

export type ThemeMode = "light" | "dark" | "system";

const STORAGE_KEY = "at.theme";

interface ThemeValue {
  mode: ThemeMode;
  resolved: "light" | "dark"; // 실제 적용된 테마
  set: (m: ThemeMode) => void;
}

const Ctx = createContext<ThemeValue | null>(null);

function loadMode(): ThemeMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === "light" || raw === "dark" || raw === "system") return raw;
  } catch {
    // ignore
  }
  return "dark";
}

function systemPrefersDark(): boolean {
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function resolve(mode: ThemeMode): "light" | "dark" {
  if (mode === "system") return systemPrefersDark() ? "dark" : "light";
  return mode;
}

function apply(resolved: "light" | "dark") {
  document.documentElement.classList.toggle("dark", resolved === "dark");
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<ThemeMode>(() => loadMode());
  const [resolved, setResolved] = useState<"light" | "dark">(() =>
    resolve(loadMode()),
  );

  // mode 변경 시 적용 + 저장
  useEffect(() => {
    const r = resolve(mode);
    setResolved(r);
    apply(r);
    try {
      localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // ignore
    }
  }, [mode]);

  // mode === "system" 일 때 OS 다크모드 변경 추적
  useEffect(() => {
    if (mode !== "system") return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => {
      const r: "light" | "dark" = mq.matches ? "dark" : "light";
      setResolved(r);
      apply(r);
    };
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [mode]);

  const set = useCallback((m: ThemeMode) => setMode(m), []);

  return <Ctx.Provider value={{ mode, resolved, set }}>{children}</Ctx.Provider>;
}

export function useTheme(): ThemeValue {
  const v = useContext(Ctx);
  if (!v) throw new Error("useTheme must be used inside <ThemeProvider>");
  return v;
}
