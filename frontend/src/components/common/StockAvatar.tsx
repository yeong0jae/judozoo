const INK_COUNT = 8;

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

export default function StockAvatar({
  name,
  code,
  size = 30,
}: {
  name: string;
  code: string;
  size?: number;
}) {
  const ch = name.trim().charAt(0) || "?";
  return (
    <span
      className="inline-flex items-center justify-center rounded-full font-semibold shrink-0"
      style={{
        width: size,
        height: size,
        background: "var(--avatar-surface)",
        color: `var(--avatar-ink-${hash(code) % INK_COUNT})`,
        boxSizing: "border-box",
        border: "1px solid var(--avatar-ring)",
        fontSize: size * 0.42,
      }}
      aria-hidden
    >
      {ch}
    </span>
  );
}
