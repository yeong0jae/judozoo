import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dist = resolve(root, "dist");
const seo = JSON.parse(readFileSync(resolve(root, "seo.json"), "utf8"));

const EXPANSIONS = {
  "/market-analysis/:slug": () =>
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

const base = readFileSync(resolve(dist, "index.html"), "utf8");

const escape = (text) =>
  text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

function swap(html, pattern, next, what) {
  if (!pattern.test(html)) fail(`index.html에서 ${what}를 못 찾았다. 태그 모양이 바뀌었다.`);
  return html.replace(pattern, () => next);
}

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
