import { useEffect, useRef, useState } from "react";
import { goLogin, useLogout, useMe } from "../../api/auth";

/**
 * 계정 버튼 — 사람 아이콘을 누르면 로그인/로그아웃이 뜬다.
 *
 * 레일(세로)과 헤더(가로)에서 같이 쓰므로 팝오버가 열리는 방향만 다르게 받는다.
 */
export default function AccountButton({
  placement = "right",
}: {
  /** right = 좌측 레일에서 오른쪽으로, bottom = 헤더에서 아래로 */
  placement?: "right" | "bottom";
}) {
  const { data: me, isLoading } = useMe();
  const logout = useLogout();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  // 바깥 클릭·Esc로 닫기
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  if (isLoading) return null;

  const authed = !!me?.authenticated;
  const panel =
    placement === "right"
      ? "left-full bottom-0 ml-2 origin-bottom-left"
      : "right-0 top-full mt-2 origin-top-right";

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label={authed ? "계정" : "로그인"}
        aria-expanded={open}
        title={me?.email ?? "로그인"}
        className={`flex h-10 w-10 items-center justify-center rounded-lg transition-colors ${
          open
            ? "bg-zinc-800 text-zinc-100"
            : `hover:bg-zinc-850 hover:text-zinc-200 ${authed ? "text-zinc-300" : "text-zinc-500"}`
        }`}
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="8" r="3.6" />
          <path d="M4.6 20c0-3.5 3.3-5.8 7.4-5.8s7.4 2.3 7.4 5.8" />
        </svg>
      </button>

      {open && (
        <div
          className={`absolute z-40 w-52 rounded-xl border border-zinc-800 bg-zinc-900 p-1.5 shadow-xl ${panel}`}
        >
          {authed ? (
            <>
              <div className="px-2.5 py-2 text-xs text-zinc-500 truncate" title={me?.email ?? undefined}>
                {me?.email}
              </div>
              <button
                type="button"
                onClick={() => {
                  setOpen(false);
                  logout.mutate();
                }}
                disabled={logout.isPending}
                className="w-full rounded-lg px-2.5 py-2 text-left text-sm text-zinc-300 hover:bg-zinc-850 disabled:opacity-50"
              >
                로그아웃
              </button>
            </>
          ) : (
            <button
              type="button"
              onClick={goLogin}
              className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-sm text-zinc-300 hover:bg-zinc-850"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden className="shrink-0">
                <path fill="#4285F4" d="M22.5 12.2c0-.8-.07-1.4-.2-2.1H12v3.9h6c-.13 1-.8 2.6-2.3 3.6l3.5 2.7c2.1-1.9 3.3-4.8 3.3-8.1z" />
                <path fill="#34A853" d="M12 23c3 0 5.5-1 7.3-2.7l-3.5-2.7c-.9.6-2.2 1.1-3.8 1.1-2.9 0-5.4-1.9-6.3-4.6l-3.6 2.8C3.9 20.5 7.6 23 12 23z" />
                <path fill="#FBBC05" d="M5.7 14.1c-.2-.7-.4-1.4-.4-2.1s.1-1.4.4-2.1L2.1 7.1C1.4 8.6 1 10.2 1 12s.4 3.4 1.1 4.9l3.6-2.8z" />
                <path fill="#EA4335" d="M12 5.3c2 0 3.4.9 4.2 1.6l3.1-3C17.5 2.2 15 1 12 1 7.6 1 3.9 3.5 2.1 7.1l3.6 2.8C6.6 7.2 9.1 5.3 12 5.3z" />
              </svg>
              Google로 로그인
            </button>
          )}
        </div>
      )}
    </div>
  );
}
