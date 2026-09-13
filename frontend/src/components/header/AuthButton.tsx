import { goLogin, useLogout, useMe } from "../../api/auth";

/** 로그인 상태에 따라 버튼 하나. 이메일은 길 수 있어 @ 앞만 보여준다. */
export default function AuthButton() {
  const { data: me, isLoading } = useMe();
  const logout = useLogout();

  if (isLoading) return null;

  if (!me?.authenticated) {
    return (
      <button
        type="button"
        onClick={goLogin}
        className="px-2.5 py-1.5 rounded-lg text-xs text-zinc-300 hover:bg-white/[0.06] border border-white/[0.06]"
      >
        로그인
      </button>
    );
  }

  return (
    <button
      type="button"
      onClick={() => logout.mutate()}
      disabled={logout.isPending}
      title={me.email ?? undefined}
      className="px-2.5 py-1.5 rounded-lg text-xs text-zinc-400 hover:bg-white/[0.06] border border-white/[0.06] disabled:opacity-50"
    >
      {me.email?.split("@")[0]} · 로그아웃
    </button>
  );
}
