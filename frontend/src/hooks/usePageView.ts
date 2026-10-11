import { useEffect } from "react";
import { useLocation } from "react-router-dom";

/**
 * 화면 전환을 Google Analytics에 알린다.
 *
 * SPA라 gtag 자동 집계는 첫 로드 한 번뿐이다 — 직접 보내지 않으면 **모든 방문이 첫 화면 하나로 뭉친다**
 * (그래서 index.html은 `send_page_view: false`).
 * 쿼리스트링은 뗀다 — utm·fbclid가 붙으면 같은 화면이 여러 줄로 갈린다.
 * gtag이 없는 환경(로컬 개발, 광고 차단)에서는 아무 일도 하지 않는다.
 */
export function usePageView(): void {
  const { pathname } = useLocation();

  useEffect(() => {
    const gtag = (window as { gtag?: (...args: unknown[]) => void }).gtag;
    if (!gtag) return;
    gtag("event", "page_view", {
      page_path: pathname,
      page_title: document.title,
    });
  }, [pathname]);
}
