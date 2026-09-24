import { useSyncExternalStore } from "react";
import { CHANGE_RATE_OPTIONS } from "../components/common/ChangeRateSelector";

/**
 * 당일 등락률 임계값(%) — 사용자가 고르고 새로고침해도 유지된다.
 *
 * 화면과 헤더 티커가 **같은 값**을 봐야 조회가 합쳐진다(쿼리 키가 이 값이라,
 * 다르면 같은 목록을 두 번 받는다). 그래서 localStorage를 각자 읽지 않고
 * 작은 store 하나를 구독한다 — 한쪽에서 바꾸면 나머지가 즉시 따라온다.
 *
 * 기본이 0인 건 **넓게 보여주고 사용자가 좁히게** 하려는 것이다. 선택지의 최댓값(7)로
 * 시작하면 처음 온 사람이 가장 좁은 목록을 먼저 보고, 홈의 주도주 카드(등락률 하한 0)에서
 * 본 종목이 목록에는 없는 일이 생긴다. 후보 필터 기준값 7.0(`settings.py`)과는 다른 값이다 —
 * 그쪽은 "주도주 후보"의 정의고 이쪽은 화면이 처음 보여줄 범위다.
 */
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
  // getSnapshot은 같은 상태면 같은 값을 돌려줘야 한다 — 매번 읽지 않고 담아 둔다.
  let current = read(key);
  const listeners = new Set<() => void>();
  const emit = () => listeners.forEach((l) => l());

  return {
    subscribe(listener: () => void) {
      listeners.add(listener);
      // 다른 탭에서 바꾼 것도 따라간다
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
        // 저장 못 해도 이번 세션 동안은 동작한다
      }
      emit();
    },
  };
}

const domestic = createStore("leadingStock.minChangeRate");
const overseas = createStore("overseasStock.minChangeRate");

/** 국내 주도주 등락률 임계값 — `useState`처럼 쓰되 값이 전역으로 공유된다. */
export function useMinChangeRate(): [number, (v: number) => void] {
  return [useSyncExternalStore(domestic.subscribe, domestic.get), domestic.set];
}

/** 해외 주도주 등락률 임계값. */
export function useOverseasMinChangeRate(): [number, (v: number) => void] {
  return [useSyncExternalStore(overseas.subscribe, overseas.get), overseas.set];
}
