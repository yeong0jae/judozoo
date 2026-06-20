// 종목 머리글자 원형 아바타 — 브랜드 로고 대용. 코드 해시로 색을 고정해 같은 종목은 늘 같은 색.
const COLORS = [
  "#F04452", "#3182F6", "#16A34A", "#F59E0B",
  "#8B5CF6", "#EC4899", "#06B6D4", "#F97316",
];

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
  const color = COLORS[hash(code) % COLORS.length];
  const ch = name.trim().charAt(0) || "?";
  return (
    <span
      className="inline-flex items-center justify-center rounded-full text-white font-semibold shrink-0"
      style={{ width: size, height: size, background: color, fontSize: size * 0.42 }}
      aria-hidden
    >
      {ch}
    </span>
  );
}
