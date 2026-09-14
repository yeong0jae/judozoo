import { CHANGE_RATE_OPTIONS } from "../components/common/ChangeRateSelector";

/**
 * 당일 등락률 임계값(%) — 사용자가 고르고 새로고침해도 유지된다.
 *
 * 주도주 화면과 헤더 티커가 같은 값을 읽어야 조회 캐시가 합쳐진다
 * (쿼리 키가 이 값이라, 다르면 같은 목록을 두 번 받는다).
 */
export const MIN_CHANGE_RATE_KEY = "leadingStock.minChangeRate";
export const OVERSEAS_MIN_CHANGE_RATE_KEY = "overseasStock.minChangeRate";

export const DEFAULT_MIN_CHANGE_RATE = 7;

function load(key: string): number {
  try {
    const raw = localStorage.getItem(key);
    const saved = Number(raw);
    return raw !== null && CHANGE_RATE_OPTIONS.includes(saved) ? saved : DEFAULT_MIN_CHANGE_RATE;
  } catch {
    return DEFAULT_MIN_CHANGE_RATE;
  }
}

export const loadMinChangeRate = () => load(MIN_CHANGE_RATE_KEY);
export const loadOverseasMinChangeRate = () => load(OVERSEAS_MIN_CHANGE_RATE_KEY);
