import { createPortal } from "react-dom";
import { useWithdraw } from "../../api/auth";

/** 되돌릴 수 없는 일이라 한 번 더 묻는다. 설정 모달과 같은 이유로 body에 포털한다. */
export default function WithdrawConfirm({ onClose }: { onClose: () => void }) {
  const withdraw = useWithdraw();
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4" onClick={onClose}>
      <div
        className="w-full max-w-sm rounded-2xl border border-zinc-800 bg-zinc-900 p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="mb-2 text-lg font-semibold text-zinc-100">정말 탈퇴할까요?</h3>
        <p className="mb-5 text-sm text-zinc-400">
          가입 기록과 보낸 의견이 바로 삭제되며 되돌릴 수 없습니다. 다시 로그인하면 새로 가입됩니다.
        </p>
        {withdraw.isError && <p className="mb-3 text-sm text-red-400">탈퇴하지 못했습니다. 잠시 뒤 다시 시도해 주세요.</p>}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded-lg px-4 py-2 text-sm text-zinc-300 hover:bg-zinc-850">
            취소
          </button>
          <button
            type="button"
            onClick={() => withdraw.mutate()}
            disabled={withdraw.isPending}
            className="rounded-lg bg-red-500/90 px-4 py-2 text-sm font-medium text-white hover:bg-red-500 disabled:opacity-50"
          >
            {withdraw.isPending ? "처리 중" : "탈퇴"}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
