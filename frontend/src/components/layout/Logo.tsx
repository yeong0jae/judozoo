/**
 * judozoo 마크 — 소문자 j의 해부도(점·기둥·갈고리)에 캔들을 겹쳤다.
 * 꼬리가 j의 점, 캔들 몸통이 기둥, 갈고리가 내려긋는 꼬리다.
 *
 * 몸통만 양봉 빨강이고 획은 `currentColor`라 테마를 따라간다 — 빨간 건 캔들이지 글자가 아니다.
 * nav 아이콘과 같은 24 그리드를 쓰되, 로고는 면으로 채워 선 아이콘들과 위계를 나눈다.
 */

// 차트가 양봉에 쓰는 값과 같은 빨강(CandleChart·IndexLineChart와 동일). 테마와 무관한 브랜드 상수다.
const UP = "#f43f5e";

export default function Logo({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden>
      <path d="M15.5 3V6.4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      <rect x="12.7" y="6.4" width="5.6" height="7.6" rx="1.4" fill={UP} />
      <path
        d="M15.5 14v1.9a3.8 3.8 0 0 1-7.6 0"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.6"
        strokeLinecap="round"
      />
    </svg>
  );
}
