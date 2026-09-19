import { useMutation } from "@tanstack/react-query";
import { apiFetch } from "./client";

/** 백엔드 `FeedbackRequest`와 같은 값이어야 한다 — 화면에서 먼저 막아 헛걸음을 줄인다. */
export const FEEDBACK_MAX_LENGTH = 2000;

export function useSendFeedback() {
  return useMutation({
    mutationFn: (content: string) =>
      apiFetch<null>("/api/feedback", {
        method: "POST",
        body: JSON.stringify({ content }),
      }),
  });
}
