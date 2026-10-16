import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const mark = JSON.parse(readFileSync(resolve(root, "src/components/layout/mark.json"), "utf8"));

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

const targets = [
  { file: "design/og-source.html", size: 84, indent: 8 },
  { file: "design/apple-touch-icon-source.html", size: 116, indent: 6 },
];

for (const { file, size, indent } of targets) {
  const path = resolve(root, file);
  const html = readFileSync(path, "utf8");
  const block = svg({ size, ink: mark.ink.light, indent });
  const region = /(<!-- mark:start -->\n\s*)[\s\S]*?(\n\s*<!-- mark:end -->)/;
  if (!region.test(html)) throw new Error(`${file}: mark:start…mark:end 구간을 못 찾았다`);
  writeFileSync(path, html.replace(region, (_m, head, tail) => head + block + tail));
}

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
