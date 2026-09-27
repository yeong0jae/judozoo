/**
 * 주도주 릴스 mp4 굽기 — 운영 타임라인 API에서 그날 데이터를 받아 `reel.html`을 한 프레임씩 찍는다.
 *
 *   node render.mjs us 2026-09-25            # → out/주도주-해외-0925.mp4
 *   node render.mjs kr 2026-09-23 --secs 8   # 앞 8초만 (확인용)
 *
 * 화면 녹화가 아니라 가짜 시계로 프레임을 넘기며 찍는다 — 1080×1920 원본 크기 그대로이고,
 * 캡처가 느려도 프레임이 빠지지 않는다. CSS 전환(자막·장면 페이드)도 같은 시계에 맞춘다.
 */
import { spawn } from "node:child_process";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import ffmpegPath from "ffmpeg-static";
import { chromium } from "playwright";

const here = dirname(fileURLToPath(import.meta.url));
const FPS = 30;

const args = process.argv.slice(2);
const flag = (name) => {
  const i = args.indexOf(name);
  return i < 0 ? undefined : args.splice(i, 2)[1];
};
const secs = Number(flag("--secs")) || null;
const api = flag("--api") ?? "https://judozoo.com";
let out = flag("--out");
const [market, date] = args;
if (!["kr", "us"].includes(market) || !/^\d{4}-\d{2}-\d{2}$/.test(date ?? "")) {
  console.error("사용법: node render.mjs <kr|us> <YYYY-MM-DD> [--secs N] [--out 파일] [--api 주소]");
  process.exit(1);
}
out ??= resolve(here, "out", `주도주-${market === "kr" ? "국내" : "해외"}-${date.slice(5).replace("-", "")}.mp4`);

/** API 응답(종목 사전 + 분마다 번호)을 화면이 쓰는 모양으로 — 이름 목록과 [시각, 번호들, 등락률들] */
async function fetchDay() {
  const res = await fetch(`${api}/api/leader-timeline?market=${market}&date=${date}`);
  if (!res.ok) throw new Error(`타임라인 API ${res.status}`);
  const { data } = await res.json();
  if (!data?.ticks?.length) throw new Error(`${market} ${date} — 찍힌 분이 없습니다`);
  return {
    stocks: data.stocks.map((s) => s.name),
    ticks: data.ticks.map((t) => [t.at, t.stocks, t.rates]),
  };
}

const data = await fetchDay();
console.log(`${market} ${date} — ${data.ticks.length}분, 종목 ${data.stocks.length}개`);

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
await page.addInitScript((reel) => { window.__REEL__ = reel; }, { market, date, data });
await page.clock.install();
await page.goto(pathToFileURL(resolve(here, "reel.html")).href);
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

mkdirSync(dirname(out), { recursive: true });
const ff = spawn(ffmpegPath, [
  "-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(FPS), "-i", "-",
  "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out,
], { stdio: ["pipe", "inherit", "inherit"] });
const done = new Promise((ok, fail) => ff.on("close", (code) => (code ? fail(new Error(`ffmpeg ${code}`)) : ok())));

const frames = Math.round(total * FPS);
for (let n = 0; n < frames; n++) {
  if (n) await page.clock.runFor(1000 / FPS);
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
