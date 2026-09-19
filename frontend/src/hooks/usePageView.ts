import { useEffect } from "react";
import { useLocation } from "react-router-dom";

/**
 * 화면 전환을 Google Analytics에 알린다.
 *
 * SPA라 주소가 바뀌어도 문서를 새로 받지 않는다. gtag의 자동 집계는 첫 로드 한 번뿐이라,
 * 전환을 직접 보내지 않으면 **모든 방문이 첫 화면 하나로 뭉친다.**
 * (index.html에서 `send_page_view: false`로 자동 집계를 끈 이유이기도 하다 — 켜둔 채
 *  여기서도 보내면 첫 화면만 두 번 세어진다.)
 *
 * 쿼리스트링은 떼고 보낸다. 홍보 링크의 utm·fbclid가 붙으면 같은 화면이 여러 줄로 갈린다 —
 * 대시보드에서 이미 겪은 것과 같은 문제다. 유입 경로는 GA가 referrer로 따로 잡는다.
 *
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
