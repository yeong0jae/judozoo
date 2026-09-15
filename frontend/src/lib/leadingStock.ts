/**
 * 거래대금 상위 몇 위까지 등락률과 무관하게 목록에 들어오는지.
 *
 * 백엔드의 `TOP_RANK_ALWAYS_INCLUDED`와 **같은 값이어야 한다** — 다르면 목록에서
 * 구분선이 엉뚱한 자리에 서거나, 기준을 통과한 종목이 "거래대금 상위"로 묶인다.
 * 응답이 거래대금 내림차순이고 강제 포함분이 맨 앞에 오는 걸 전제로 한다.
 */
export const ALWAYS_INCLUDED_RANKS = 2;

/** "거래대금 1, 2위" — 위 상수에서 만들어 둘이 어긋나지 않게 한다. */
export const ALWAYS_INCLUDED_LABEL = `거래대금 ${Array.from(
  { length: ALWAYS_INCLUDED_RANKS },
  (_, i) => i + 1,
).join(", ")}위`;
