import { useState } from "react";
import { useTheme, type ThemeMode } from "../../theme/theme";

export default function SettingsButton() {
  const [open, setOpen] = useState(false);

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="flex items-center justify-center w-8 h-8 rounded hover:bg-zinc-800 text-zinc-400 hover:text-zinc-200 transition-colors"
        aria-label="설정"
      >
        ⚙
      </button>
      {open && <SettingsModal onClose={() => setOpen(false)} />}
    </>
  );
}

function SettingsModal({ onClose }: { onClose: () => void }) {
  return (
    <div
      className="fixed inset-0 bg-black/70 flex items-center justify-center z-50"
      onClick={onClose}
    >
      <div
        className="bg-zinc-900 border border-zinc-800 rounded-lg p-6 max-w-md w-full"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold">설정</h3>
          <button
            onClick={onClose}
            className="text-zinc-500 hover:text-zinc-200"
          >
            ×
          </button>
        </div>
        <div className="space-y-3">
          <ThemeRow />
        </div>
      </div>
    </div>
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
    <div className="p-3 -mx-3">
      <div className="text-sm mb-2">테마</div>
      <div className="flex gap-1 p-1 rounded-lg bg-zinc-800 border border-zinc-700">
        {options.map((o) => (
          <button
            key={o.value}
            type="button"
            onClick={() => set(o.value)}
            className={`flex-1 px-3 py-1.5 rounded text-xs font-medium transition-colors ${
              mode === o.value
                ? "bg-blue-500 text-white"
                : "text-zinc-300 hover:text-zinc-100"
            }`}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}
