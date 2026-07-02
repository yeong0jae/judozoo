import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { QK } from "./queries";
import type { DailyIssueItem } from "../types";

export function useCaptureThemes() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () =>
      apiFetch<number>("/api/themes/capture", { method: "POST" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["themes", "calendar"] });
    },
  });
}

/** 이슈 작성·수정·삭제 — 성공 시 그날 이슈 목록을 무효화해 다시 읽는다. */
export function useAddIssue(date: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (content: string) =>
      apiFetch<DailyIssueItem>("/api/issues", {
        method: "POST",
        body: JSON.stringify({ date, content }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: QK.issues(date) }),
  });
}

export function useEditIssue(date: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, content }: { id: number; content: string }) =>
      apiFetch<DailyIssueItem>(`/api/issues/${id}`, {
        method: "PUT",
        body: JSON.stringify({ content }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: QK.issues(date) }),
  });
}

export function useDeleteIssue(date: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => apiFetch<void>(`/api/issues/${id}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: QK.issues(date) }),
  });
}
