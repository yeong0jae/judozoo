import { useMutation, useQuery } from "@tanstack/react-query";
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
  return useMutation({
    mutationFn: () => apiFetch<null>("/api/auth/logout", { method: "POST" }),
    // 첫 화면으로 전체 이동한다. 로그인이 주소창 이동이라 대칭이고,
    // 관문 뒤 화면에 머무는 것(쓸 수 없는 화면)과 캐시 잔존을 한 번에 없앤다.
    onSettled: () => {
      window.location.href = "/";
    },
  });
}

/** 탈퇴 — 가입 기록과 보낸 의견이 지워지고 세션도 비워진다. 끝나면 로그아웃처럼 첫 화면으로 전체 이동한다. */
export function useWithdraw() {
  return useMutation({
    mutationFn: () => apiFetch<null>("/api/auth/withdraw", { method: "POST" }),
    onSuccess: () => {
      window.location.href = "/";
    },
  });
}
