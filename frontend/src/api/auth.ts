import { useMutation, useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";

export type Me = { authenticated: boolean; email: string | null };

export const AUTH_QK = ["auth", "me"] as const;

export function useMe() {
  return useQuery({
    queryKey: AUTH_QK,
    queryFn: () => apiFetch<Me>("/api/auth/me"),
    staleTime: 60_000,
    retry: false,
  });
}

export function goLogin(): void {
  window.location.href = "/api/auth/login";
}

export function useLogout() {
  return useMutation({
    mutationFn: () => apiFetch<null>("/api/auth/logout", { method: "POST" }),
    onSettled: () => {
      window.location.href = "/";
    },
  });
}

export function useWithdraw() {
  return useMutation({
    mutationFn: () => apiFetch<null>("/api/auth/withdraw", { method: "POST" }),
    onSuccess: () => {
      window.location.href = "/";
    },
  });
}
