import { useEffect } from "react";
import { useLocation } from "react-router-dom";
import { NAV } from "../components/layout/nav";
import { INDICES } from "../lib/indices";

const BRAND = "judozoo";

const DOCUMENT_TITLES: Record<string, string> = {
  "/terms": "이용약관",
  "/privacy": "개인정보 처리방침",
};

export function useTabTitle() {
  const { pathname } = useLocation();

  useEffect(() => {
    const name =
      NAV.find((n) => n.to === pathname)?.label ??
      DOCUMENT_TITLES[pathname] ??
      INDICES.find((i) => pathname === `/market-analysis/${i.slug}`)?.name;
    document.title = name ? `${name} : ${BRAND}` : BRAND;
  }, [pathname]);
}
