import { useEffect } from "react";

/**
 * ↑/↓ 방향키로 종목 목록 선택을 이동한다. 선택된 종목 행으로 스크롤도 맞춘다.
 * - [codes]: 화면 표시 순서의 종목코드(중복은 내부에서 제거 — 실시간 로그처럼 한 종목이 여러 행일 때 대비).
 * - 입력 요소(INPUT/TEXTAREA/SELECT) 포커스 중에는 무시.
 * - 스크롤 타깃은 `[data-stock-code="..."]` — 각 행에 이 속성을 달아둬야 한다.
 */
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
