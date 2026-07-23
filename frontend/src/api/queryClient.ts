import { QueryClient } from "@tanstack/react-query";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5_000,
      // 창 복귀 시 재조회 — 탭/앱 전환으로 refetchInterval이 멈춘 사이 낡은 값을 최신화한다.
      refetchOnWindowFocus: true,
      retry: 1,
    },
  },
});
