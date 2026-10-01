/**
 * 주도주 릴스 mp4 굽기 — 운영 타임라인 API에서 그날 데이터를 받아 `reel.vN.html`을 한 프레임씩 찍는다.
 *
 *   node render.mjs 2026-09-23               # 최신 버전 → out/2026-09-23/주도주-국내-0923-v4.mp4
 *   node render.mjs 2026-09-23 --v 1         # 예전 버전으로
 *   node render.mjs 2026-09-23 --secs 8      # 앞 8초만 (확인용)
 *   node render.mjs 2026-09-23 --fps 60      # 60fps → out/2026-09-23/주도주-국내-0923-v4-60fps.mp4 (굽는 시간 두 배)
 *
 * 국내만 굽는다. 화면에는 여전히 `market: "kr"`을 넘긴다 — 옛 버전(v1~v3)이 시장을 받아서 고른다.
 *
 * 화면 녹화가 아니라 가짜 시계로 프레임을 넘기며 찍는다 — 1080×1920 원본 크기 그대로이고,
 * 캡처가 느려도 프레임이 빠지지 않는다. CSS 전환(자막·장면 페이드)도 같은 시계에 맞춘다.
 */
import { spawn } from "node:child_process";
import { mkdirSync, readdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import ffmpegPath from "ffmpeg-static";
import { chromium } from "playwright";
import { writeCaption } from "./caption.mjs";

const here = dirname(fileURLToPath(import.meta.url));

const args = process.argv.slice(2);
const flag = (name) => {
  const i = args.indexOf(name);
  return i < 0 ? undefined : args.splice(i, 2)[1];
};
const secs = Number(flag("--secs")) || null;
const FPS = Number(flag("--fps") ?? 30);
const api = flag("--api") ?? "https://judozoo.com";
const versions = readdirSync(here).map((f) => f.match(/^reel\.v(\d+)\.html$/)?.[1]).filter(Boolean).map(Number);
const version = Number(flag("--v") ?? Math.max(...versions));
let out = flag("--out");
const market = "kr";
const [date] = args;
if (!/^\d{4}-\d{2}-\d{2}$/.test(date ?? "") || !versions.includes(version)) {
  console.error(`사용법: node render.mjs <YYYY-MM-DD> [--v ${versions.sort((a, b) => a - b).join("|")}] [--secs N] [--fps 30|60] [--out 파일] [--api 주소]`);
  process.exit(1);
}
// 날짜별 폴더 — 같은 날 버전이 한곳에 모인다
out ??= resolve(here, "out", date, `주도주-국내-${date.slice(5).replace("-", "")}-v${version}${FPS === 30 ? "" : `-${FPS}fps`}.mp4`);
// 본문(txt)이 영상보다 먼저 이 폴더에 쓰인다 — 여기서 만든다. 이미 있으면 그대로 쓴다
mkdirSync(dirname(out), { recursive: true });

/** API 응답(종목 사전 + 분마다 번호)을 화면이 쓰는 모양으로 — 이름 목록과 [시각, 번호들, 등락률들] */
async function fetchDay() {
  const res = await fetch(`${api}/api/leader-timeline?market=${market}&date=${date}`);
  if (!res.ok) throw new Error(`타임라인 API ${res.status}`);
  const { data } = await res.json();
  if (!data?.ticks?.length) throw new Error(`${date} — 찍힌 분이 없습니다`);
  return {
    stocks: data.stocks,
    data: { stocks: data.stocks.map((s) => s.name), ticks: data.ticks.map((t) => [t.at, t.stocks, t.rates]) },
  };
}

const { stocks, data } = await fetchDay();
console.log(`${date} v${version} — ${data.ticks.length}분, 종목 ${data.stocks.length}개`);

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
await page.addInitScript((reel) => { window.__REEL__ = reel; }, { market, date, data });
await page.clock.install();
await page.goto(pathToFileURL(resolve(here, `reel.v${version}.html`)).href);
// 본문 — 영상보다 먼저 남긴다(굽는 데 수 분 걸린다). 계산을 내주지 않는 옛 버전은 건너뛴다
const facts = await page.evaluate(() => window.__REEL_FACTS);
if (facts) {
  const txt = await writeCaption({ api, date, stocks, facts, out: out.replace(/\.mp4$/, ".txt") })
    .catch((e) => console.warn(`본문을 만들지 못했습니다 — ${e.message}`));
  if (txt) console.log(`본문 — ${txt}`);
} else {
  console.log(`v${version}은 본문을 만들지 않습니다`);
}
// 캔버스 글자는 DOM에 안 쓰인 굵기를 스스로 불러오지 않는다 — 쓰는 굵기를 미리 다 받아 둔다
await page.evaluate(async () => {
  const faces = [["Noto Sans KR", [300, 400, 500, 600, 700]], ["JetBrains Mono", [200, 300, 400, 500]]];
  await Promise.all(faces.flatMap(([f, ws]) => ws.map((w) => document.fonts.load(`${w} 40px '${f}'`, "가A1%"))));
  await document.fonts.ready;
});
// 시계를 멈춘 뒤에 재생한다 — 흐르게 두면 캡처가 느린 사이 재생이 끝나 버린다
await page.clock.pauseAt(await page.evaluate(() => Date.now() + 1000));
const total = secs ?? (await page.evaluate(() => window.__REEL_SECONDS)) + 0.3;
await page.evaluate(() => { window.__start = new WeakMap(); window.__play(); });

const ff = spawn(ffmpegPath, [
  "-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(FPS), "-i", "-",
  "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out,
], { stdio: ["pipe", "inherit", "inherit"] });
const done = new Promise((ok, fail) => ff.on("close", (code) => (code ? fail(new Error(`ffmpeg ${code}`)) : ok())));

const frames = Math.round(total * FPS);
for (let n = 0; n < frames; n++) {
  // 60fps면 한 프레임이 16.67ms라 매번 반올림하면 시계가 밀린다 — 누적 시각을 기준으로 넘긴다
  if (n) await page.clock.runFor(Math.round((n * 1000) / FPS) - Math.round(((n - 1) * 1000) / FPS));
  // CSS 전환은 실제 시간으로 흐른다 — 처음 본 순간을 기억해 두고 가짜 시계만큼만 진행시킨다
  await page.evaluate(() => {
    const now = performance.now();
    for (const a of document.getAnimations()) {
      if (!window.__start.has(a)) window.__start.set(a, now);
      a.pause();
      a.currentTime = now - window.__start.get(a);
    }
  });
  const png = await page.screenshot({ animations: "allow" });
  if (!ff.stdin.write(png)) await new Promise((r) => ff.stdin.once("drain", r));
  if (n % 150 === 0) process.stdout.write(`\r${n}/${frames} 프레임`);
}
ff.stdin.end();
await done;
await browser.close();
console.log(`\r${frames}/${frames} 프레임 — ${out}`);
