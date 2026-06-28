import { useEffect } from "react";

const BASE_TITLE = "주도주 매매 도우미";

export function useTabTitle() {
  useEffect(() => {
    document.title = BASE_TITLE;
  }, []);
}
