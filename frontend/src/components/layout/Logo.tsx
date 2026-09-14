import mark from "./mark.json";

/**
 * judozoo 마크 — 소문자 j의 해부도(점·기둥·갈고리)에 캔들을 겹쳤다.
 * 꼬리가 j의 점, 캔들 몸통이 기둥, 갈고리가 내려긋는 꼬리다.
 *
 * 몸통만 양봉 빨강이고 획은 `currentColor`라 테마를 따라간다 — 빨간 건 캔들이지 글자가 아니다.
 * nav 아이콘과 같은 24 그리드를 쓰되, 로고는 면으로 채워 선 아이콘들과 위계를 나눈다.
 *
 * 좌표는 `mark.json`이 혼자 갖는다. 파비콘·OG·앱아이콘도 같은 파일에서
 * `npm run mark`로 만들어지므로, 모양을 바꿀 때 여기만 고치면 된다.
 */
export default function Logo({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox={mark.viewBox} aria-hidden>
      <path
        d={mark.dot.d}
        fill="none"
        stroke="currentColor"
        strokeWidth={mark.dot.strokeWidth}
        strokeLinecap="round"
      />
      <rect
        x={mark.body.x}
        y={mark.body.y}
        width={mark.body.width}
        height={mark.body.height}
        rx={mark.body.rx}
        fill={mark.up}
      />
      <path
        d={mark.hook.d}
        fill="none"
        stroke="currentColor"
        strokeWidth={mark.hook.strokeWidth}
        strokeLinecap="round"
      />
    </svg>
  );
}
