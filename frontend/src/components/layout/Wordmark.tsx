import Logo from "./Logo";

/**
 * 마크 + judozoo 글자를 나란히 둔 가로 락업.
 *
 * 표기는 소문자로 고정한다 — 도메인·약관 본문과 같고, 마크가 소문자 j의 해부도라서다.
 * 레일(80px)에는 글자가 들어가지 않으므로 마크만 쓰고, 이 락업은 가로가 넉넉한 자리에만 쓴다.
 */
export default function Wordmark({ size = 24 }: { size?: number }) {
  return (
    <span className="flex items-center gap-2 text-zinc-100">
      <Logo size={size} />
      <span
        className="font-brand font-semibold tracking-[-0.035em] leading-none"
        style={{ fontSize: size * 0.78 }}
      >
        judozoo
      </span>
    </span>
  );
}
