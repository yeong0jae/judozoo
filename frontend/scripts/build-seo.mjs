/**
 * 경로별 정적 HTML과 sitemap.xml을 굽는다. `vite build` 뒤에 돈다.
 *
 * SPA라 서버가 내려주는 HTML이 전 경로 동일했다. 검색 결과 제목·설명과 링크 미리보기는
 * **JS를 돌리지 않는 쪽**(카톡·슬랙·크롤러의 첫 수집)이 읽으므로, 그 자리에서 이미 달라야 한다.
 * 그래서 `dist/index.html`의 <head>만 경로별로 갈아끼워 `dist/<경로>/index.html`로 굽는다.
 * **본문은 굽지 않는다** — 장중 실시간 값이라 구워두면 곧 거짓이 된다.
 *
 * 문구의 출처는 `seo.json` 한 벌이다. NAV 라벨과 공유하지 않는다 — 메뉴는 레일 폭 때문에
 * 4자고 검색 결과 제목은 문장이라 목적이 다르다.
 *
 * 부산물로 nginx가 `try_files $uri $uri/index.html =404`를 쓸 수 있게 된다. 그래서
 * **경로 하나가 빠지면 그 화면은 404가 된다** — 아래 가드가 그걸 사용자보다 먼저 잡는다.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dist = resolve(root, "dist");
const seo = JSON.parse(readFileSync(resolve(root, "seo.json"), "utf8"));

/** 동적 라우트를 실제 경로로 펴는 규칙. 여기 없는 `:param` 라우트가 있으면 빌드를 세운다. */
const EXPANSIONS = {
  "/market-analysis/:slug": () =>
    // 지수 목록은 `indices.ts`가 단일 출처다. 여기에 다시 적으면 지수를 더할 때 갈라진다.
    [...read("src/lib/indices.ts").matchAll(/slug:\s*"([^"]+)"/g)].map(
      (m) => `/market-analysis/${m[1]}`,
    ),
};

function read(relative) {
  return readFileSync(resolve(root, relative), "utf8");
}

function fail(message) {
  console.error(`\n[build-seo] ${message}\n`);
  process.exit(1);
}

// ── 1. 가드 — App.tsx의 라우트와 seo.json이 어긋나면 빌드를 세운다 ──────────────
const declared = [...read("src/App.tsx").matchAll(/<Route\s+path="([^"]+)"/g)].map((m) => m[1]);

const routes = declared.flatMap((path) => {
  if (!path.includes(":")) return [path];
  const expand = EXPANSIONS[path];
  if (!expand) fail(`확장 규칙이 없는 동적 라우트: ${path}\nEXPANSIONS에 펴는 방법을 더한다.`);
  return expand();
});

const missing = routes.filter((path) => !(path in seo.routes));
if (missing.length) {
  fail(
    `seo.json에 없는 라우트: ${missing.join(", ")}\n` +
      `그대로 두면 정적 HTML이 안 구워져 직접 접속이 404가 된다. 제목·설명을 더한다.`,
  );
}

const stale = Object.keys(seo.routes).filter((path) => !routes.includes(path));
if (stale.length) {
  fail(
    `App.tsx에 없는 경로가 seo.json에 남아 있다: ${stale.join(", ")}\n` +
      `그대로 두면 화면이 없는 URL이 200으로 색인된다. seo.json에서 지운다.`,
  );
}

// ── 2. 경로별 HTML ────────────────────────────────────────────────────────────
const base = readFileSync(resolve(dist, "index.html"), "utf8");

const escape = (text) =>
  text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** 안 바뀐 걸 모르고 지나가면 그 경로만 조용히 옛 문구로 나간다. 못 찾으면 세운다. */
function swap(html, pattern, next, what) {
  if (!pattern.test(html)) fail(`index.html에서 ${what}를 못 찾았다. 태그 모양이 바뀌었다.`);
  // 치환 문자열의 `$`가 패턴 참조로 읽히지 않게 함수로 넘긴다.
  return html.replace(pattern, () => next);
}

/** 구글에 사이트 이름과 로고를 말해준다. 첫 화면에만 넣는다. */
const jsonLd = JSON.stringify([
  { "@context": "https://schema.org", "@type": "WebSite", name: "judozoo", url: `${seo.origin}/` },
  {
    "@context": "https://schema.org",
    "@type": "Organization",
    name: "judozoo",
    url: `${seo.origin}/`,
    logo: `${seo.origin}/apple-touch-icon.png`,
  },
]);

function render(path, meta) {
  const url = seo.origin + path;
  const canonical = seo.origin + (meta.canonical ?? path);
  let html = base;
  html = swap(html, /<title>[\s\S]*?<\/title>/, `<title>${escape(meta.title)}</title>`, "<title>");
  html = swap(
    html,
    /<meta\s+name="description"[\s\S]*?\/>/,
    `<meta name="description" content="${escape(meta.description)}" />`,
    "description",
  );
  html = swap(
    html,
    /<meta\s+property="og:url"[\s\S]*?\/>/,
    `<meta property="og:url" content="${url}" />`,
    "og:url",
  );
  html = swap(
    html,
    /<meta\s+property="og:title"[\s\S]*?\/>/,
    `<meta property="og:title" content="${escape(meta.title)}" />`,
    "og:title",
  );
  html = swap(
    html,
    /<meta\s+property="og:description"[\s\S]*?\/>/,
    `<meta property="og:description" content="${escape(meta.description)}" />`,
    "og:description",
  );
  const head = [`<link rel="canonical" href="${canonical}" />`];
  if (path === "/") head.push(`<script type="application/ld+json">${jsonLd}</script>`);
  return swap(html, /<\/head>/, `  ${head.join("\n    ")}\n  </head>`, "</head>");
}

for (const [path, meta] of Object.entries(seo.routes)) {
  const html = render(path, meta);
  if (path === "/") {
    writeFileSync(resolve(dist, "index.html"), html);
    continue;
  }
  const dir = resolve(dist, path.slice(1));
  mkdirSync(dir, { recursive: true });
  writeFileSync(resolve(dir, "index.html"), html);
}

// ── 3. sitemap.xml ────────────────────────────────────────────────────────────
// canonical이 남을 가리키는 경로(`/market-analysis`)는 뺀다. 화면은 있어야 하므로 위에서 굽긴 했다.
const listed = Object.entries(seo.routes).filter(([, meta]) => meta.sitemap !== false);
const lastmod = new Date().toISOString().slice(0, 10);
const sitemap = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
  ...listed.map(
    ([path]) => `  <url><loc>${seo.origin}${path}</loc><lastmod>${lastmod}</lastmod></url>`,
  ),
  "</urlset>",
  "",
].join("\n");
writeFileSync(resolve(dist, "sitemap.xml"), sitemap);

console.log(`[build-seo] HTML ${Object.keys(seo.routes).length}개, sitemap ${listed.length}개`);
