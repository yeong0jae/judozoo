import { useState } from "react";
import { useSettings, type SettingsKey } from "../../settings/settings";
import { useTheme, type ThemeMode } from "../../theme/theme";

export default function SettingsButton() {
  const [open, setOpen] = useState(false);
  const settings = useSettings();

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="flex items-center justify-center w-8 h-8 rounded hover:bg-zinc-800 text-zinc-400 hover:text-zinc-200 transition-colors"
        aria-label="설정"
      >
        ⚙
      </button>
      {open && (
        <SettingsModal
          onClose={() => setOpen(false)}
          settings={settings}
        />
      )}
    </>
  );
}

function SettingsModal({
  onClose,
  settings,
}: {
  onClose: () => void;
  settings: ReturnType<typeof useSettings>;
}) {
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
          <Toggle
            label="OS 데스크톱 알림"
            hint="브라우저가 백그라운드여도 OS 알림으로 종료를 인지"
            value={settings.osNotificationsEnabled}
            onChange={(v) => handleToggle("osNotificationsEnabled", v, settings.set)}
          />
          <Toggle
            label="사운드 알림"
            hint="사이클 종료 시 소리"
            value={settings.soundEnabled}
            onChange={(v) => settings.set("soundEnabled", v)}
          />
          <Toggle
            label="UNCLOSED / NO_FILL 강조"
            hint="운영 점검이 필요한 종료를 빨강으로 강조"
            value={settings.emphasizeUnclosed}
            onChange={(v) => settings.set("emphasizeUnclosed", v)}
          />
        </div>
      </div>
    </div>
  );
}

async function handleToggle(
  key: SettingsKey,
  value: boolean,
  set: (k: SettingsKey, v: boolean) => void,
) {
  if (key === "osNotificationsEnabled" && value) {
    if (!("Notification" in window)) {
      alert("이 브라우저는 OS 알림을 지원하지 않습니다");
      return;
    }
    const result = await Notification.requestPermission();
    if (result !== "granted") {
      alert("OS 알림 권한이 거부되었습니다");
      return;
    }
  }
  set(key, value);
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

function Toggle({
  label,
  hint,
  value,
  onChange,
}: {
  label: string;
  hint?: string;
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label className="flex items-start justify-between gap-4 cursor-pointer p-3 rounded hover:bg-zinc-800/50 -mx-3">
      <div className="flex-1">
        <div className="text-sm">{label}</div>
        {hint && <div className="text-xs text-zinc-500 mt-0.5">{hint}</div>}
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={value}
        onClick={() => onChange(!value)}
        className={`shrink-0 mt-0.5 w-10 h-6 rounded-full p-0.5 transition-colors ${
          value ? "bg-blue-500" : "bg-gray-300"
        }`}
      >
        <span
          className={`block w-5 h-5 bg-white rounded-full transition-transform ${
            value ? "translate-x-4" : ""
          }`}
        />
      </button>
    </label>
  );
}
