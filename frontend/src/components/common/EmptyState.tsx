import type { ReactNode } from "react";

interface Props {
  icon?: string;
  message: string;
  action?: ReactNode;
}

export default function EmptyState({ icon = "📭", message, action }: Props) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <div className="text-4xl mb-3 opacity-60" aria-hidden="true">
        {icon}
      </div>
      <p className="text-sm text-zinc-500 mb-4">{message}</p>
      {action}
    </div>
  );
}
