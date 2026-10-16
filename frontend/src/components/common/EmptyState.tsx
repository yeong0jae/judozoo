import type { ReactNode } from "react";

interface Props {
  message: string;
  hint?: ReactNode;
  action?: ReactNode;
}

export default function EmptyState({ message, hint, action }: Props) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <p className="text-sm text-zinc-500">{message}</p>
      {hint && <p className="mt-2 max-w-md text-xs leading-relaxed text-zinc-600">{hint}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
