import { useMutation } from "@tanstack/react-query";
import { apiFetch } from "./client";

export const FEEDBACK_MAX_LENGTH = 500;

export function useSendFeedback() {
  return useMutation({
    mutationFn: (content: string) =>
      apiFetch<null>("/api/feedback", {
        method: "POST",
        body: JSON.stringify({ content }),
      }),
  });
}
