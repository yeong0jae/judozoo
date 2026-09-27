# 주도주 릴스

주도주 타임라인 하루를 1080×1920 세로 영상(mp4, 30fps, 약 41초)으로 굽는다.

```bash
cd reels
npm install                          # 처음 한 번 — playwright, ffmpeg, 글꼴
npx playwright install chromium      # 처음 한 번

node render.mjs us 2026-09-25        # → out/주도주-해외-0925.mp4
node render.mjs kr 2026-09-23        # → out/주도주-국내-0923.mp4
node render.mjs kr 2026-09-23 --secs 8   # 앞 8초만 — 화면 확인용
```

- 데이터는 운영 타임라인 API(`/api/leader-timeline`)에서 받는다. 날짜는 각 시장의 현지 날짜 — 해외는 뉴욕 날짜
- 한 편에 수 분 걸린다. 화면 녹화가 아니라 가짜 시계로 한 프레임씩 넘기며 찍어서, 원본 크기 그대로이고 프레임이 빠지지 않는다
- 소리는 없다. 음악은 올릴 때 붙인다
- 영상은 `out/`에 남는다(git에서 뺐다)

## 화면

`reel.html` 하나다. 여는 장면 4.6초(1위 바통), 본 장면 30초(하루 흐름 + 굵직한 자막), 닫는 장면 5초(마감 순위 · 주소).
직접 열면 데이터가 없어 빈 화면이다 — `render.mjs`가 `window.__REEL__`에 데이터를 넣고 `__play()`를 부른다.

시장 구간은 앱 타임라인(`frontend/src/lib/leaderTimeline.ts`)과 같다 — 국내 08:00~20:00, 해외 04:00~20:00(뉴욕).
