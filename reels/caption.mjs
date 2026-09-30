/**
 * 릴스 본문(txt) — 영상과 같은 계산(`window.__REEL_FACTS`)에 "왜 오르나" 사유를 붙여 영상 옆에 남긴다.
 *
 * 위는 그대로 붙여 넣을 본문, 아래는 올리기 전에 확인할 근거(키워드·기사)다. 근거 기사 제목·링크는
 * 로그인해야 API에 실린다 — 브라우저의 `judozoo_session` 쿠키 값을 `JUDOZOO_SESSION`에 넣으면 붙는다.
 * 사유 API는 날짜를 받지 않고 **지금** 것만 준다. 장 마감 뒤 그날 안에 구워야 맞는 사유가 붙는다.
 */
import { writeFileSync } from "node:fs";

const LABEL = {
  kr: { market: "국내장", tags: ["#국내주식", "#국장"] },
  us: { market: "미국장", tags: ["#미국주식", "#미장"] },
};

const pct = (r) => `${r >= 0 ? "+" : ""}${r.toFixed(2)}%`;
const hashtag = (name) => `#${name.replace(/[\s()·.&]/g, "")}`;

async function fetchReasons(api, market, session) {
  const res = await fetch(`${api}/api/insight/reasons?market=${market}`, {
    headers: session ? { cookie: `judozoo_session=${session}` } : {},
  });
  if (!res.ok) throw new Error(`사유 API ${res.status}`);
  const { data } = await res.json();
  return new Map(data.map((r) => [r.code, r]));
}

/** 사유가 그 날짜 것인지 — 해외는 뉴욕 날짜라 한국 시각으로는 다음 날 새벽까지 이어진다 */
function isOfDate(generatedAt, date) {
  const kst = generatedAt.slice(0, 10);
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return kst === date || kst === next.toISOString().slice(0, 10);
}

function body({ market, date, stocks, facts, reasons }) {
  const { market: where, tags } = LABEL[market];
  const closeRank = new Map(facts.close.map((c, n) => [c.k, n + 1]));
  const rateOf = (k) => facts.close.find((c) => c.k === k)?.rate;
  // 주인공(폭발 종목)과 마감 1위. 같으면 마감 2위를 더한다
  const picks = [...new Set([facts.boom, facts.close[0]?.k, facts.close[1]?.k].filter((k) => k !== null && k !== undefined))]
    .slice(0, 2);
  // 훅은 마감 등락률로만 — "하루 만에"라고 말하므로 장중 최고를 쓰면 틀린다
  const best = Math.max(...picks.map((k) => rateOf(k) ?? -Infinity));
  const [, m, d] = date.split("-").map(Number);

  const lines = [];
  if (best >= 10) lines.push(`하루 만에 +${Math.floor(best)}%?! 😳`);
  lines.push(`${m}/${d} ${where} 주도주 — ${picks.map((k) => stocks[k].name).join(" & ")}`, "");
  for (const k of picks) {
    const rank = closeRank.get(k);
    const r = reasons.get(stocks[k].code);
    const why = r?.explained ? ` | ${r.reason}` : "";
    const rate = rank ? pct(rateOf(k)) : `장중 최고 ${pct(facts.peak[k])}`;
    lines.push(`${rank === 1 ? "👑" : "🚀"} ${stocks[k].name} ${rate}${why}`);
  }
  lines.push("");
  if (facts.relay.length > 1) lines.push(`주도 흐름: ${facts.relay.map((k) => stocks[k].name).join(" → ")}`);
  lines.push("📍 judozoo.com", "", "※ 제공되는 정보는 투자 권유가 아니며, 투자 판단과 책임은 이용자 본인에게 있습니다.", "");
  lines.push([...tags, "#주도주", ...picks.map((k) => hashtag(stocks[k].name)), "#급등주"].join(" "));
  return { text: lines.join("\n"), picks };
}

function evidence({ date, stocks, picks, reasons, session }) {
  const lines = ["", "", "──────── 근거 — 올리기 전 확인용, 본문에 넣지 않는다 ────────"];
  for (const k of picks) {
    const { name, code } = stocks[k];
    const r = reasons.get(code);
    lines.push("", `${name} (${code})`);
    if (!r) { lines.push("  사유 없음 — 지금 사유 목록에 이 종목이 없습니다(사유 API는 오늘 것만 주므로 지난 날짜면 비어 있습니다)"); continue; }
    lines.push(`  기준 ${r.generatedAt.replace("T", " ").slice(0, 16)} (한국 시각)`);
    if (!isOfDate(r.generatedAt, date)) lines.push(`  ⚠️ ${date} 사유가 아닐 수 있습니다 — 사유 API는 지금 것만 줍니다`);
    if (!r.explained) { lines.push("  뉴스로 설명되지 않음"); continue; }
    lines.push(`  키워드: ${r.keywords.join(", ")}`, `  사유: ${r.reason}`);
    for (const [label, list, count] of [["근거 기사", r.evidence, r.evidenceCount], ["관련 기사", r.related, r.relatedCount]]) {
      if (!count) continue;
      if (!list) {
        lines.push(`  ${label} ${count}건 — 제목·링크는 로그인 쿠키가 있어야 받습니다 (${session ? "쿠키가 만료됐거나 틀렸습니다" : "JUDOZOO_SESSION 없음"})`);
        continue;
      }
      lines.push(`  ${label} ${count}건`);
      list.forEach((a) => lines.push(`  - [${a.source}] ${a.title}`, `    ${a.url}`));
    }
  }
  return lines.join("\n");
}

export async function writeCaption({ api, market, date, stocks, facts, out }) {
  const session = process.env.JUDOZOO_SESSION;
  const reasons = await fetchReasons(api, market, session);
  const { text, picks } = body({ market, date, stocks, facts, reasons });
  writeFileSync(out, `${text}${evidence({ date, stocks, picks, reasons, session })}\n`);
  return out;
}
