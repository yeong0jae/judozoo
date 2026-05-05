export default function Skeleton({ className = "" }: { className?: string }) {
  return (
    <div
      className={`bg-zinc-800/60 rounded animate-pulse ${className}`}
      aria-hidden="true"
    />
  );
}
