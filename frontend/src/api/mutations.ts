import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { QK } from "./queries";
import type {
  CancelTradingResult,
  CreateTradingRequest,
  CreateTradingResult,
  LiquidateHoldingResult,
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

export function useLiquidateHolding() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (stockCode: string) =>
      apiFetch<LiquidateHoldingResult>(
        `/api/account/holdings/${stockCode}/sell`,
        { method: "POST" },
      ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: QK.holdings });
      qc.invalidateQueries({ queryKey: QK.accountBalance });
    },
  });
}

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

/** 그 날짜 신호 라벨링(멱등) — 끝나면 해당 날짜 분석을 새로고침. */
export function useLabelSignals() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (date: string) =>
      apiFetch<number>(`/api/signal-analysis/label?date=${date}`, {
        method: "POST",
      }),
    onSuccess: (_, date) => {
      qc.invalidateQueries({
        queryKey: ["leading-stocks", "signal-analysis", date],
      });
    },
  });
}
