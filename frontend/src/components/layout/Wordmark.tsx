import Logo from "./Logo";

/**
 * judozoo 워드마크 — 마크가 첫 글자 `j`를 대신한다.
 *
 * 마크의 해부도가 소문자 j와 같아서(꼬리=점, 몸통=기둥, 갈고리=내려긋는 꼬리)
 * 글자 자리에 그대로 끼울 수 있다. 그래서 뒤에 오는 글자는 "udozoo"다.
 *
 * 자리 잡는 규칙 두 가지:
 *  - 세로: 마크 안에서 기둥이 끝나는 지점(y=13.4/24 ≈ 55.8%)이 글자 베이스라인에 와야 한다.
 *    baseline 정렬은 요소의 아래끝을 베이스라인에 두므로, 그만큼 아래로 내린다.
 *  - 가로: 24 그리드에서 마크의 실제 획은 x 7.9~18.3 구간뿐이라 양옆이 비어 있다.
 *    음수 마진으로 그 여백을 걷어내야 글자와 자간이 맞는다.
 */
export default function Wordmark({ size = 24 }: { size?: number }) {
  return (
    <span
      className="flex items-baseline font-brand font-semibold tracking-[-0.035em] leading-none text-zinc-100"
      style={{ fontSize: size }}
      aria-label="judozoo"
    >
      <Logo size={size * 1.55} className="-ml-[0.42em] -mr-[0.3em] translate-y-[0.685em]" />
      <span aria-hidden>udozoo</span>
    </span>
  );
}
