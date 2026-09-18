/**
 * 종목 머리글자 원형 아바타 — 브랜드 로고 대용. 코드 해시로 색을 고정해 같은 종목은 늘 같은 색.
 *
 * **면은 무채색 하나, 색은 글자에만 준다.** 이 화면에서 채도는 이미 뜻을 갖고 있다 —
 * 빨강·파랑은 등락률, amber는 신규 진입, violet은 승격, 시안은 코스닥, 주황은 돌파.
 * 아바타가 같은 채도의 원색을 깔면 뜻 없는 색이 신호처럼 읽힌다. 글자 한 겹이면
 * 종목을 구분하기엔 충분하고, 옆 칸의 숫자와 경쟁하지도 않는다.
 *
 * 면·테두리·글자 세 값 모두 index.css의 테마 토큰이다. 규칙은 양쪽이 같고 명도만
 * 뒤집힌다 — 밝은 면에 300톤 글자를 얹으면 읽히지 않으므로 라이트는 700톤을 쓴다.
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
