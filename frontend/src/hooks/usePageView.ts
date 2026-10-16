import { useEffect } from "react";
import { useLocation } from "react-router-dom";

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
