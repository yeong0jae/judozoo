/**
 * 종목 머리글자 원형 아바타 — 브랜드 로고 대용. 코드 해시로 색을 고정해 같은 종목은 늘 같은 색.
 *
 * **면은 무채색 하나, 색은 글자에만 준다.** 이 화면에서 채도는 이미 뜻을 갖고 있어(등락률·신규 진입·
 * 승격·코스닥·돌파) 원색 면을 깔면 뜻 없는 색이 신호처럼 읽힌다.
 * 세 값 모두 index.css의 테마 토큰이고, 라이트는 읽히도록 700톤 글자를 쓴다.
 */
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
        // 테두리가 크기를 늘리지 않게 — 목록의 칸 폭이 아바타 크기에 맞춰져 있다
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
