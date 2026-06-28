import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";

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
