import {
  createContext,
  useCallback,
  useContext,
  useRef,
  useState,
  type ReactNode,
} from "react";
export type ToastTone = "success" | "error" | "warning" | "info";

export interface ToastInput {
  message: string;
  tone?: ToastTone;
  duration?: number;
  action?: { label: string; onClick: () => void };
}

interface Toast extends ToastInput {
  id: number;
}

interface ToastContextValue {
  show: (input: ToastInput) => number;
  dismiss: (id: number) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

const DEFAULT_DURATION: Record<ToastTone, number> = {
  success: 3000,
  error: 6000,
  warning: 4000,
  info: 3000,
};

const MAX_VISIBLE = 3;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const idRef = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const show = useCallback(
    (input: ToastInput) => {
      const id = ++idRef.current;
      const tone = input.tone ?? "info";
      const duration = input.duration ?? DEFAULT_DURATION[tone];
      setToasts((prev) => [...prev, { ...input, id, tone }]);
      if (duration > 0) {
        setTimeout(() => dismiss(id), duration);
      }
      return id;
    },
    [dismiss],
  );

  return (
    <ToastContext.Provider value={{ show, dismiss }}>
      {children}
      <ToastContainer toasts={toasts} dismiss={dismiss} />
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used inside <ToastProvider>");
  return ctx;
}

const TONE_CLS: Record<ToastTone, string> = {
  success: "bg-emerald-50 border-emerald-200 text-emerald-800",
  error: "bg-rose-50 border-rose-200 text-rose-700",
  warning: "bg-amber-50 border-amber-200 text-amber-800",
  info: "bg-zinc-900 border-zinc-700 text-zinc-100",
};

function ToastContainer({
  toasts,
  dismiss,
}: {
  toasts: Toast[];
  dismiss: (id: number) => void;
}) {
  const visible = toasts.slice(-MAX_VISIBLE);
  const overflow = toasts.length - visible.length;
  return (
    <div className="fixed bottom-6 right-6 flex flex-col gap-2 z-50 w-80 pointer-events-none">
      {overflow > 0 && (
        <div className="text-center text-xs text-zinc-500 bg-zinc-900 border border-zinc-800 rounded px-3 py-1 pointer-events-auto">
          +{overflow}개 알림 더보기
        </div>
      )}
      {visible.map((t) => (
        <ToastCard key={t.id} toast={t} onClose={() => dismiss(t.id)} />
      ))}
    </div>
  );
}

function ToastCard({ toast, onClose }: { toast: Toast; onClose: () => void }) {
  const tone = toast.tone ?? "info";
  const cls = TONE_CLS[tone];
  return (
    <div
      className={`border rounded-lg shadow-lg px-4 py-3 pointer-events-auto ${cls}`}
      role="status"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <p className="text-sm leading-snug">{toast.message}</p>
          {toast.action && (
            <button
              onClick={toast.action.onClick}
              className="mt-2 text-xs underline text-zinc-300 hover:text-zinc-100"
            >
              {toast.action.label}
            </button>
          )}
        </div>
        <button
          onClick={onClose}
          aria-label="닫기"
          className="text-zinc-500 hover:text-zinc-200 leading-none -mt-0.5"
        >
          ×
        </button>
      </div>
    </div>
  );
}
