/**
 * 지수·수급 화면이 다루는 지수 목록.
 *
 * 화면(스트립)·라우트·탭 제목이 모두 여기를 본다. 지수를 더하면 셋이 한꺼번에 따라온다.
 * 페이지 모듈이 아니라 여기 두는 이유는 탭 제목 훅도 이 목록이 필요하기 때문이다 —
 * 훅이 1,400줄짜리 페이지를 끌어다 쓰지 않게 한다.
 */

/** `slug`가 URL이자 탭 제목의 출처다. `id`는 화면 내부 분기에만 쓴다. */
export type IndexInfo = { id: string; slug: string; name: string; delayed?: boolean };

export const INDICES: IndexInfo[] = [
  { id: "kospi", slug: "kospi", name: "코스피" },
  { id: "kospiF", slug: "kospi-futures", name: "코스피 선물" },
  { id: "kosdaq", slug: "kosdaq", name: "코스닥" },
  { id: "kosdaqF", slug: "kosdaq-futures", name: "코스닥 선물" },
  { id: "nightF", slug: "night-futures", name: "코스피 야간 선물" },
  { id: "nasdaq", slug: "nasdaq", name: "나스닥" },
  // 지표 하나가 아니라 원달러·WTI 묶음이라 스트립에서 전용 칸을 쓴다.
  { id: "macro", slug: "macro", name: "매크로" },
];

// 지수는 전부 로그인 없이 본다 — 시세와 차트, 투자자 수급까지.
// (예전에는 야간 선물만 공개라 "공개 지수로 보내기" 분기가 필요했다.)

const DEFAULT_SLUG = "kospi";
const LAST_SLUG_KEY = "market-analysis:slug";

/** `/market-analysis`로 그냥 들어오면 마지막에 보던 지수로 보낸다. */
export function loadLastSlug(): string {
  try {
    const raw = localStorage.getItem(LAST_SLUG_KEY);
    return raw && INDICES.some((i) => i.slug === raw) ? raw : DEFAULT_SLUG;
  } catch {
    return DEFAULT_SLUG;
  }
}

export function rememberSlug(slug: string): void {
  try {
    localStorage.setItem(LAST_SLUG_KEY, slug);
  } catch {
    // 저장 못 해도 화면은 그대로 돈다
  }
}
