/**
 * 마크가 들어가는 정적 파일들을 `src/components/layout/mark.json`에서 다시 만든다.
 *
 * 파비콘과 design/*.html은 번들 밖이라 Logo.tsx를 못 가져다 쓴다. 그래서 좌표를
 * 한 곳에 두고 이 스크립트가 찍어낸다 — 모양을 바꾸면 `npm run mark` 한 번.
 *
 * design/*.html의 마크는 `<!-- mark:start -->`와 `<!-- mark:end -->` 사이만 갈아끼운다.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const mark = JSON.parse(readFileSync(resolve(root, "src/components/layout/mark.json"), "utf8"));

/** 마크 하나. [ink]는 획 색 — 파비콘은 CSS 변수를, 나머지는 리터럴을 넣는다. */
function svg({ size, ink, indent }) {
  const pad = " ".repeat(indent);
  const { dot, body, hook, up, viewBox } = mark;
  return [
    `<svg width="${size}" height="${size}" viewBox="${viewBox}" aria-hidden="true">`,
    `  <path d="${dot.d}" fill="none" stroke="${ink}" stroke-width="${dot.strokeWidth}" stroke-linecap="round"/>`,
    `  <rect x="${body.x}" y="${body.y}" width="${body.width}" height="${body.height}" rx="${body.rx}" fill="${up}"/>`,
    `  <path d="${hook.d}" fill="none" stroke="${ink}" stroke-width="${hook.strokeWidth}" stroke-linecap="round"/>`,
    `</svg>`,
  ]
    .map((line, i) => (i === 0 ? line : pad + line))
    .join("\n");
}

// ── 파비콘 — 탭 배경 밝기에 따라 획 색이 바뀐다 ──────────────
const favicon = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${mark.viewBox}">
  <!-- 생성 파일 — 고치지 말 것. src/components/layout/mark.json을 고치고 \`npm run mark\`. -->
  <style>
    :root { --ink: ${mark.ink.light}; }
    @media (prefers-color-scheme: dark) { :root { --ink: ${mark.ink.dark}; } }
  </style>
${svg({ size: 24, ink: "var(--ink)", indent: 0 }).split("\n").slice(1, -1).join("\n")}
</svg>
`;
writeFileSync(resolve(root, "public/favicon.svg"), favicon);

// ── design/*.html — 내보내기용 원본. 배경이 흰색이라 획은 밝은 테마 색 고정 ──
const targets = [
  { file: "design/og-source.html", size: 84, indent: 8 },
  { file: "design/apple-touch-icon-source.html", size: 116, indent: 6 },
];

for (const { file, size, indent } of targets) {
  const path = resolve(root, file);
  const html = readFileSync(path, "utf8");
  const block = svg({ size, ink: mark.ink.light, indent });
  // 이미 최신이면 결과가 그대로다 — "안 바뀌었다"를 실패로 보면 두 번째 실행에서 죽는다.
  // 바뀌었는지가 아니라 구간을 찾았는지로 판정한다.
  const region = /(<!-- mark:start -->\n\s*)[\s\S]*?(\n\s*<!-- mark:end -->)/;
  if (!region.test(html)) throw new Error(`${file}: mark:start…mark:end 구간을 못 찾았다`);
  writeFileSync(path, html.replace(region, (_m, head, tail) => head + block + tail));
}

// ── design/icon-*.svg — 정사각 아이콘 원본 ────────────────────
//
// 파비콘과 달리 **마크를 캔버스 정중앙에 놓는다.** 뷰박스 중심(12,12)과 잉크 중심은
// 다르다 — stroke 반폭과 round 캡까지 넣으면 잉크는 x 6.6~18.3, y 2.0~21.0 이고
// 중심이 (12.45, 11.50)이다. 그냥 얹으면 살짝 어긋나 보인다.
//
// 마크 높이를 캔버스의 64%로 잡아 위아래 여백을 18%씩 둔다. 모서리는 굴리지 않는다 —
// iOS·안드로이드가 알아서 깎는다.
const INK = { cx: 12.45, cy: 11.5, height: 19.0 };
const SIDE = INK.height / 0.64;
const [vx, vy] = [INK.cx - SIDE / 2, INK.cy - SIDE / 2];
const f = (n) => n.toFixed(3);

function squareIcon({ ink, bg }) {
  const plate = bg
    ? `\n  <rect x="${f(vx)}" y="${f(vy)}" width="${f(SIDE)}" height="${f(SIDE)}" fill="${bg}"/>`
    : "";
  const body = svg({ size: 1024, ink, indent: 0 }).split("\n").slice(1, -1).join("\n");
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${f(vx)} ${f(vy)} ${f(SIDE)} ${f(SIDE)}" width="1024" height="1024">
  <!-- 생성 파일 — 고치지 말 것. src/components/layout/mark.json을 고치고 \`npm run mark\`. -->${plate}
${body}
</svg>
`;
}

const icons = {
  "design/icon-light.svg": { ink: mark.ink.light, bg: "#ffffff" },
  "design/icon-dark.svg": { ink: mark.ink.dark, bg: mark.ink.light },
  "design/icon-clear.svg": { ink: mark.ink.light, bg: null },
};
for (const [file, opts] of Object.entries(icons)) {
  writeFileSync(resolve(root, file), squareIcon(opts));
}

console.log(
  "마크를 다시 만들었다 — public/favicon.svg, " +
    targets.map((t) => t.file).join(", ") +
    ", " +
    Object.keys(icons).join(", "),
);
