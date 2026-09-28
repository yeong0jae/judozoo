import { useState } from "react";
import { createPortal } from "react-dom";
import { useTheme, type ThemeMode } from "../../theme/theme";
import FeedbackModal from "./FeedbackModal";

export default function SettingsButton() {
  const [open, setOpen] = useState(false);
  const [feedbackOpen, setFeedbackOpen] = useState(false);
  // 쓰던 의견은 버튼 쪽에 둔다 — 모달은 바깥을 누르면 닫히는데, 그때 초안까지 사라지면
  // 잘못 눌렀다가 처음부터 다시 써야 한다.
  const [feedback, setFeedback] = useState("");

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className={`flex h-10 w-10 items-center justify-center rounded-lg transition-colors ${
          open || feedbackOpen ? "bg-zinc-800 text-zinc-100" : "text-zinc-500 hover:bg-zinc-850 hover:text-zinc-200"
        }`}
        aria-label="설정"
      >
        {/* nav 아이콘과 같은 24 그리드·1.8 획 */}
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="3.2" />
          <path d="M19.1 14.4a1.7 1.7 0 0 0 .34 1.87l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1.03 1.56V21a2 2 0 1 1-4 0v-.11a1.7 1.7 0 0 0-1.11-1.56 1.7 1.7 0 0 0-1.87.34l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.7 1.7 0 0 0 .34-1.87 1.7 1.7 0 0 0-1.56-1.03H3a2 2 0 1 1 0-4h.11a1.7 1.7 0 0 0 1.56-1.11 1.7 1.7 0 0 0-.34-1.87l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.7 1.7 0 0 0 1.87.34h.08A1.7 1.7 0 0 0 10.14 3.1V3a2 2 0 1 1 4 0v.11a1.7 1.7 0 0 0 1.03 1.56 1.7 1.7 0 0 0 1.87-.34l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.7 1.7 0 0 0-.34 1.87v.08a1.7 1.7 0 0 0 1.56 1.03H21a2 2 0 1 1 0 4h-.11a1.7 1.7 0 0 0-1.56 1.03z" />
        </svg>
      </button>
      {open && (
        <SettingsModal
          onClose={() => setOpen(false)}
          onFeedback={() => {
            setOpen(false);
            setFeedbackOpen(true);
          }}
        />
      )}
      {feedbackOpen && (
        <FeedbackModal
          content={feedback}
          onChange={setFeedback}
          onClose={() => setFeedbackOpen(false)}
          onSent={() => {
            setFeedback("");
            setFeedbackOpen(false);
          }}
        />
      )}
    </>
  );
}

/**
 * 설정 모달.
 *
 * body로 포털한다 — 트리거가 좌측 레일 안에 있어서, 그대로 두면 `fixed`가
 * 레일 폭에 갇혀 모달이 찌그러진다.
 */
function SettingsModal({ onClose, onFeedback }: { onClose: () => void; onFeedback: () => void }) {
  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-2xl border border-zinc-800 bg-zinc-900 p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-lg font-semibold text-zinc-100">설정</h3>
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
        <ThemeRow />
        <button
          type="button"
          onClick={onFeedback}
          className="mt-5 w-full rounded-xl bg-zinc-800 px-3 py-2.5 text-sm text-zinc-300 transition-colors hover:text-zinc-100"
        >
          의견 보내기
        </button>
      </div>
    </div>,
    document.body,
  );
}

function ThemeRow() {
  const { mode, set } = useTheme();
  const options: { value: ThemeMode; label: string }[] = [
    { value: "light", label: "라이트" },
    { value: "dark", label: "다크" },
    { value: "system", label: "시스템" },
  ];
  return (
    <div>
      <div className="mb-2 text-sm text-zinc-300">테마</div>
      {/* 앱의 다른 세그먼트와 같은 규칙 — 눌린 트랙 위로 선택 칸이 떠오른다 */}
      <div className="flex gap-1 rounded-xl bg-zinc-800 p-1">
        {options.map((o) => (
          <button
            key={o.value}
            type="button"
            onClick={() => set(o.value)}
            className={`flex-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
              mode === o.value
                ? "bg-elevated text-zinc-100"
                : "text-zinc-500 hover:text-zinc-300"
            }`}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}
