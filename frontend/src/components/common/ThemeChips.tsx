/** 테마 칩 묶음 — 대표 테마 + "+N" 표기. */
export default function ThemeChips({
  themes,
  themeCount,
}: {
  themes: string[];
  themeCount: number;
}) {
  if (themes.length === 0) return null;
  const extra = themeCount - themes.length;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {themes.map((t) => (
        <span
          key={t}
          className="text-[11px] px-2 py-0.5 rounded-full bg-white/[0.06] text-zinc-400"
        >
          {t}
        </span>
      ))}
      {extra > 0 && <span className="text-[11px] text-zinc-500">+{extra}</span>}
    </div>
  );
}
