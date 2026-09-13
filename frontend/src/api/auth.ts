import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";

export type Me = { authenticated: boolean; email: string | null };

export const AUTH_QK = ["auth", "me"] as const;

/** 로그인 상태. 미로그인도 200이라 에러가 아니라 `authenticated: false`로 온다. */
export function useMe() {
  return useQuery({
    queryKey: AUTH_QK,
    queryFn: () => apiFetch<Me>("/api/auth/me"),
    staleTime: 60_000,
    retry: false,
  });
}

/** 서버가 구글로 302를 보낸다. fetch로는 리다이렉트를 따라갈 수 없어 주소창을 넘긴다. */
export function goLogin(): void {
  window.location.href = "/api/auth/login";
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch<null>("/api/auth/logout", { method: "POST" }),
    // 관문 뒤 데이터가 캐시에 남아 있으면 로그아웃 후에도 잠깐 보인다.
    onSuccess: () => qc.clear(),
  });
}
