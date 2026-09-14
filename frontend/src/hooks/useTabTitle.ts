import { useEffect } from "react";
import { useLocation } from "react-router-dom";
import { NAV } from "../components/layout/nav";

const BRAND = "judozoo";

/** 레일에 없는 문서 화면. 나머지 이름은 NAV에서 가져와 메뉴명과 어긋나지 않게 한다. */
const DOCUMENT_TITLES: Record<string, string> = {
  "/terms": "이용약관",
  "/privacy": "개인정보 처리방침",
};

/** 보고 있는 화면 이름을 탭 제목에 붙인다 — "주도주 : judozoo". 이름이 없는 경로는 브랜드만. */
export function useTabTitle() {
  const { pathname } = useLocation();

  useEffect(() => {
    const name = NAV.find((n) => n.to === pathname)?.label ?? DOCUMENT_TITLES[pathname];
    document.title = name ? `${name} : ${BRAND}` : BRAND;
  }, [pathname]);
}
