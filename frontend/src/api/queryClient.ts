import { QueryClient } from "@tanstack/react-query";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5_000,
      // 창 복귀 시 재조회 — 탭/앱 전환으로 refetchInterval이 멈춘 사이 낡은 값을 최신화한다.
      refetchOnWindowFocus: true,
      // 포커스가 없어도 refetchInterval을 계속 돌린다 — 듀얼모니터에서 보기만 하는 창도 실시간 유지.
      refetchIntervalInBackground: true,
      retry: 1,
    },
  },
});
