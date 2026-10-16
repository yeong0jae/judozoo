export type IndexInfo = { id: string; slug: string; name: string; delayed?: boolean };

export const INDICES: IndexInfo[] = [
  { id: "kospi", slug: "kospi", name: "코스피" },
  { id: "kospiF", slug: "kospi-futures", name: "코스피 선물" },
  { id: "kosdaq", slug: "kosdaq", name: "코스닥" },
  { id: "kosdaqF", slug: "kosdaq-futures", name: "코스닥 선물" },
  { id: "nightF", slug: "night-futures", name: "코스피 야간 선물" },
  { id: "nasdaq", slug: "nasdaq", name: "나스닥" },
  { id: "nasdaqF", slug: "nasdaq-futures", name: "나스닥 선물", delayed: true },
  { id: "macro", slug: "macro", name: "매크로" },
];

const DEFAULT_SLUG = "kospi";
const LAST_SLUG_KEY = "market-analysis:slug";

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
  }
}
