import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { QK } from "./queries";
import type {
  CancelTradingResult,
  CreateTradingRequest,
  CreateTradingResult,
} from "../types";

export function useCreateCommand() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: CreateTradingRequest) =>
      apiFetch<CreateTradingResult>("/api/trading", {
        method: "POST",
        body: JSON.stringify(req),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: QK.activeCommands });
      qc.invalidateQueries({ queryKey: QK.accountBalance });
    },
  });
}

export function useCancelCommand() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      apiFetch<CancelTradingResult>(`/api/trading/${id}`, {
        method: "DELETE",
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: QK.activeCommands });
      qc.invalidateQueries({ queryKey: QK.todayClosed });
    },
  });
}
