import { useMutation, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { QK } from "./queries";
export function useCreateCommand() {
    const qc = useQueryClient();
    return useMutation({
        mutationFn: (req) => apiFetch("/api/trading", {
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
        mutationFn: (id) => apiFetch(`/api/trading/${id}`, {
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
        mutationFn: (stockCode) => apiFetch(`/api/account/holdings/${stockCode}/sell`, { method: "POST" }),
        onSuccess: () => {
            qc.invalidateQueries({ queryKey: QK.holdings });
            qc.invalidateQueries({ queryKey: QK.accountBalance });
        },
    });
}
