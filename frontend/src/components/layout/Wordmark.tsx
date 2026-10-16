import Logo from "./Logo";

export default function Wordmark({ size = 24 }: { size?: number }) {
  return (
    <span className="flex items-center gap-2 text-zinc-100">
      <Logo size={size} />
      <span
        className="font-brand font-semibold tracking-[-0.035em] leading-none"
        style={{ fontSize: size * 0.78 }}
      >
        judozoo
      </span>
    </span>
  );
}
