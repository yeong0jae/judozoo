import { goLogin } from "../../api/auth";

/** 구글 로그인 버튼 — 관문(LoginGate)과 첫 화면 소개 띠가 함께 쓴다. */
export default function GoogleLoginButton() {
  return (
    <button
      type="button"
      onClick={goLogin}
      className="flex items-center gap-2 px-4 py-2 rounded-lg bg-zinc-950 border border-zinc-800 text-zinc-100 text-sm font-medium hover:bg-zinc-850"
    >
      <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden>
        <path fill="#4285F4" d="M22.5 12.2c0-.8-.07-1.4-.2-2.1H12v3.9h6c-.13 1-.8 2.6-2.3 3.6l3.5 2.7c2.1-1.9 3.3-4.8 3.3-8.1z" />
        <path fill="#34A853" d="M12 23c3 0 5.5-1 7.3-2.7l-3.5-2.7c-.9.6-2.2 1.1-3.8 1.1-2.9 0-5.4-1.9-6.3-4.6l-3.6 2.8C3.9 20.5 7.6 23 12 23z" />
        <path fill="#FBBC05" d="M5.7 14.1c-.2-.7-.4-1.4-.4-2.1s.1-1.4.4-2.1L2.1 7.1C1.4 8.6 1 10.2 1 12s.4 3.4 1.1 4.9l3.6-2.8z" />
        <path fill="#EA4335" d="M12 5.3c2 0 3.4.9 4.2 1.6l3.1-3C17.5 2.2 15 1 12 1 7.6 1 3.9 3.5 2.1 7.1l3.6 2.8C6.6 7.2 9.1 5.3 12 5.3z" />
      </svg>
      Google로 로그인
    </button>
  );
}
