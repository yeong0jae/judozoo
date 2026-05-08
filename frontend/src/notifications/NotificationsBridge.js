// /topic/trading/lifecycle 구독 → NotificationProvider + OS 알림 + 사운드.
// App.tsx에서 ToastProvider/NotificationProvider 안쪽에 두 번 마운트해야 hook 사용 가능.
import { useStompSubscription } from "../ws/useStompSubscription";
import { useNotifications } from "./notifications";
import { useToast } from "../components/toast/Toast";
import { useSettings } from "../settings/settings";
export default function NotificationsBridge() {
    const notifications = useNotifications();
    const toast = useToast();
    const settings = useSettings();
    useStompSubscription("/topic/trading/lifecycle", (p) => {
        if (p.type !== "CLOSED")
            return;
        const ts = p.ts ?? new Date().toISOString();
        notifications.add({
            ts,
            cycleId: p.cycleId,
            closeReason: p.closeReason,
        });
        const isCritical = p.closeReason === "UNCLOSED" || p.closeReason === "NO_FILL";
        const message = `명령 #${p.cycleId} 종료 — ${p.closeReason}`;
        toast.show({
            message,
            closeReason: p.closeReason,
            tone: isCritical ? "warning" : "info",
        });
        if (settings.osNotificationsEnabled && "Notification" in window) {
            if (Notification.permission === "granted") {
                new Notification("AT 자동매매", {
                    body: message,
                    tag: `cmd-${p.cycleId}`,
                    requireInteraction: isCritical,
                });
            }
        }
        if (settings.soundEnabled) {
            playBeep(isCritical);
        }
    });
    return null;
}
function playBeep(critical) {
    try {
        const AudioCtx = window
            .AudioContext ||
            window
                .webkitAudioContext;
        if (!AudioCtx)
            return;
        const ctx = new AudioCtx();
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.frequency.value = critical ? 660 : 880;
        gain.gain.value = 0.05;
        osc.start();
        osc.stop(ctx.currentTime + 0.18);
    }
    catch {
        // ignore
    }
}
