import { useSyncExternalStore } from "react";
import { CHANGE_RATE_OPTIONS } from "../components/common/ChangeRateSelector";

export const DEFAULT_MIN_CHANGE_RATE = 0;

function read(key: string): number {
  try {
    const raw = localStorage.getItem(key);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : DEFAULT_MIN_CHANGE_RATE;
  } catch {
    return DEFAULT_MIN_CHANGE_RATE;
  }
}

function createStore(key: string) {
  let current = read(key);
  const listeners = new Set<() => void>();
  const emit = () => listeners.forEach((l) => l());

  return {
    subscribe(listener: () => void) {
      listeners.add(listener);
      const onStorage = (e: StorageEvent) => {
        if (e.key !== key) return;
        current = read(key);
        emit();
      };
      window.addEventListener("storage", onStorage);
      return () => {
        listeners.delete(listener);
        window.removeEventListener("storage", onStorage);
      };
    },
    get: () => current,
    set(value: number) {
      if (value === current) return;
      current = value;
      try {
        localStorage.setItem(key, String(value));
      } catch {
      }
      emit();
    },
  };
}

const domestic = createStore("leadingStock.minChangeRate");
const overseas = createStore("overseasStock.minChangeRate");

export function useMinChangeRate(): [number, (v: number) => void] {
  return [useSyncExternalStore(domestic.subscribe, domestic.get), domestic.set];
}

export function useOverseasMinChangeRate(): [number, (v: number) => void] {
  return [useSyncExternalStore(overseas.subscribe, overseas.get), overseas.set];
}
