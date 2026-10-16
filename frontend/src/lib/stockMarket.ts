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
  }
}
