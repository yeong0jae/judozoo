/**
 * 종목 머리글자 원형 아바타 — 브랜드 로고 대용. 코드 해시로 색을 고정해 같은 종목은 늘 같은 색.
 *
 * **면은 무채색 하나, 색은 글자에만 준다.** 이 화면에서 채도는 이미 뜻을 갖고 있다 —
 * 빨강·파랑은 등락률, amber는 신규 진입, violet은 승격, 시안은 코스닥, 주황은 돌파.
 * 아바타가 같은 채도의 원색을 깔면 뜻 없는 색이 신호처럼 읽힌다. 글자 한 겹이면
 * 종목을 구분하기엔 충분하고, 옆 칸의 숫자와 경쟁하지도 않는다.
 */
const SURFACE = "#2b2d33";

/** 테두리 — 면과 바탕의 밝기 차가 작아 원이 번진다. 윤곽만 세우되 색은 늘리지 않는다.
 *  카드 경계(8%)·행 구분선(5.5%)과 같은 재질의 선이다. */
const RING = "rgba(255, 255, 255, 0.1)";

/** 글자색 — 앱의 칩들이 쓰는 "색 계열 밝은 톤"과 같은 자리의 색. */
const INK = [
  "#fda4af", "#93c5fd", "#86efac", "#fcd34d",
  "#c4b5fd", "#f9a8d4", "#67e8f9", "#fdba74",
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
  const color = INK[hash(code) % INK.length];
  const ch = name.trim().charAt(0) || "?";
  return (
    <span
      className="inline-flex items-center justify-center rounded-full font-semibold shrink-0"
      style={{
        width: size,
        height: size,
        background: SURFACE,
        color,
        // 테두리가 크기를 늘리지 않게 — 목록의 칸 폭이 아바타 크기에 맞춰져 있다
        boxSizing: "border-box",
        border: `1px solid ${RING}`,
        fontSize: size * 0.42,
      }}
      aria-hidden
    >
      {ch}
    </span>
  );
}
