import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useMe } from "../../api/auth";
import { FEEDBACK_MAX_LENGTH, useSendFeedback } from "../../api/feedback";
import { ApiError } from "../../api/client";
import GoogleLoginButton from "../common/GoogleLoginButton";
import { useToast } from "../toast/Toast";

/** 빈 칸에 연하게 뜨는 예시. 무엇을 적어야 할지 모르는 것이 백지 앞의 가장 큰 벽이다. */
const EXAMPLES = [
  "예) 눌림·돌파에서 뒤로 가면 스크롤이 맨 위로 올라가요",
  "예) 삼성전자 수급 숫자가 다른 곳과 달라요",
  "예) 관심 종목만 모아 보는 화면이 있으면 좋겠어요",
].join("\n");

/**
 * 의견 보내기 — 편지 아이콘을 누르면 모달이 뜬다.
 *
 * 설정·계정과 같은 자리(좌측 레일 하단, 모바일은 상단바)에 선다. 푸터의 메일 주소는
 * 그대로 두되, 메일 클라이언트를 여는 대신 여기서 바로 쓰고 끝낼 수 있게 한다.
 */
export default function FeedbackButton() {
  const [open, setOpen] = useState(false);
  // 쓰던 글은 버튼 쪽에 둔다 — 모달은 바깥을 누르면 닫히는데, 그때 초안까지 사라지면
  // 잘못 눌렀다가 처음부터 다시 써야 한다.
  const [content, setContent] = useState("");

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className={`flex h-10 w-10 items-center justify-center rounded-lg transition-colors ${
          open ? "bg-zinc-800 text-zinc-100" : "text-zinc-500 hover:bg-zinc-850 hover:text-zinc-200"
        }`}
        aria-label="의견 보내기"
        title="의견 보내기"
      >
        {/* nav 아이콘과 같은 24 그리드·1.8 획 */}
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <rect x="2.8" y="5" width="18.4" height="14" rx="2.4" />
          <path d="M3.4 7.2 12 13.2l8.6-6" />
        </svg>
      </button>
      {open && (
        <FeedbackModal
          content={content}
          onChange={setContent}
          onClose={() => setOpen(false)}
          onSent={() => {
            setContent("");
            setOpen(false);
          }}
        />
      )}
    </>
  );
}

/**
 * 의견 모달. 설정 모달과 같은 이유로 body에 포털한다 — 트리거가 좌측 레일 안이라
 * 그대로 두면 `fixed`가 레일 폭에 갇힌다.
 */
function FeedbackModal({
  content,
  onChange,
  onClose,
  onSent,
}: {
  content: string;
  onChange: (v: string) => void;
  onClose: () => void;
  onSent: () => void;
}) {
  const { data: me } = useMe();
  const send = useSendFeedback();
  const toast = useToast();

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const authed = !!me?.authenticated;
  const 보낼_수_있다 = authed && content.trim().length > 0 && !send.isPending;

  const submit = () => {
    if (!보낼_수_있다) return;
    send.mutate(content, {
      onSuccess: () => {
        toast.show({ message: "소중한 의견 감사합니다.", tone: "success" });
        onSent();
      },
      onError: (e) => {
        // 세션이 끊긴 것과 서버가 실패한 것은 다음 행동이 다르다.
        const 로그인_끊김 = e instanceof ApiError && e.status === 401;
        toast.show({
          message: 로그인_끊김
            ? "로그인 후 다시 시도해 주세요."
            : "보내지 못했습니다. 잠시 뒤 다시 시도해주세요.",
          tone: "error",
        });
      },
    });
  };

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4"
      onClick={onClose}
    >
      <div
        className="w-full max-w-lg rounded-2xl border border-zinc-800 bg-zinc-900 p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-lg font-semibold text-zinc-100">의견 보내기</h3>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="flex h-8 w-8 items-center justify-center rounded-lg text-zinc-500 hover:bg-zinc-850 hover:text-zinc-200"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>

        {authed ? (
          <>
            <p className="mb-3 text-sm text-zinc-400">
              불편한 점, 틀린 숫자, 있었으면 하는 화면 — 무엇이든 좋습니다.
            </p>
            <textarea
              autoFocus
              value={content}
              onChange={(e) => onChange(e.target.value.slice(0, FEEDBACK_MAX_LENGTH))}
              rows={8}
              placeholder={EXAMPLES}
              className="w-full resize-none rounded-xl border border-zinc-800 bg-zinc-950 p-3 text-sm text-zinc-100 placeholder:text-zinc-600 focus:border-zinc-700 focus:outline-none"
            />
            <div className="mt-3 flex items-center justify-between gap-3">
              <span className="text-xs text-zinc-600">
                {content.length} / {FEEDBACK_MAX_LENGTH}
              </span>
              <button
                type="button"
                onClick={submit}
                disabled={!보낼_수_있다}
                className="rounded-lg bg-zinc-100 px-4 py-2 text-sm font-medium text-zinc-900 hover:bg-white disabled:cursor-not-allowed disabled:opacity-40"
              >
                {send.isPending ? "보내는 중" : "보내기"}
              </button>
            </div>
          </>
        ) : (
          <div className="flex flex-col items-center gap-4 py-6 text-center">
            <p className="max-w-xs text-sm text-zinc-500">
              의견을 보내려면 로그인이 필요합니다.
            </p>
            <GoogleLoginButton />
          </div>
        )}
      </div>
    </div>,
    document.body,
  );
}
