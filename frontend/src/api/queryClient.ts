import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "./client";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5_000,
      // 창 복귀 시 재조회 — 탭/앱 전환으로 refetchInterval이 멈춘 사이 낡은 값을 최신화한다.
      refetchOnWindowFocus: true,
      // 401은 재시도해도 같은 답이다. 관문 뒤 화면에서 불필요한 호출이 두 배로 늘지 않게 막는다.
      retry: (count, err) => (err instanceof ApiError && err.status === 401 ? false : count < 1),
    },
  },
});
