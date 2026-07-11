import type { ReactNode } from "react";

interface Props {
  message: string;
  action?: ReactNode;
}

export default function EmptyState({ message, action }: Props) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <p className="text-sm text-zinc-500 mb-4">{message}</p>
      {action}
    </div>
  );
}
