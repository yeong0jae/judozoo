/**
 * 주도주 국내/해외 전환값 — 새로고침해도 마지막에 보던 쪽이 유지된다.
 *
 * 목록 화면과 홈이 같은 키를 본다. 홈에서 "해외 전체 보기"로 들어가면
 * 목록이 해외로 열려 있어야 해서다.
 */
export type StockMarket = "domestic" | "overseas";

const KEY = "leadingStock.market";

export function loadMarket(): StockMarket {
  try {
    return localStorage.getItem(KEY) === "overseas" ? "overseas" : "domestic";
  } catch {
    return "domestic";
  }
}

export function rememberMarket(market: StockMarket): void {
  try {
    localStorage.setItem(KEY, market);
  } catch {
    // 저장 못 해도 이번 세션 동안은 동작한다
  }
}
