import { useEffect } from "react";

export function useArrowStockNav(
  codes: string[],
  selectedCode: string | null,
  onSelect: (code: string) => void,
) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      const list = Array.from(new Set(codes));
      if (list.length === 0) return;
      e.preventDefault();
      const idx = list.indexOf(selectedCode ?? "");
      const next =
        e.key === "ArrowDown"
          ? Math.min((idx < 0 ? -1 : idx) + 1, list.length - 1)
          : Math.max((idx < 0 ? list.length : idx) - 1, 0);
      const code = list[next];
      onSelect(code);
      document.querySelector(`[data-stock-code="${code}"]`)?.scrollIntoView({ block: "nearest" });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [codes, selectedCode, onSelect]);
}
