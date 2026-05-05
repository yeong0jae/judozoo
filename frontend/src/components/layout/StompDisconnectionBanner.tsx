import { useEffect, useRef, useState } from "react";
import { useStompState } from "../../ws/stompMock";

export default function StompDisconnectionBanner() {
  const { state } = useStompState();
  const [showRecovered, setShowRecovered] = useState(false);
  const prevState = useRef(state);

  useEffect(() => {
    if (prevState.current !== "connected" && state === "connected") {
      setShowRecovered(true);
      const t = setTimeout(() => setShowRecovered(false), 1500);
      prevState.current = state;
      return () => clearTimeout(t);
    }
    prevState.current = state;
  }, [state]);

  if (state === "connected" && !showRecovered) return null;

  if (showRecovered) {
    return (
      <div className="bg-emerald-900/40 border-b border-emerald-800 text-emerald-200 text-xs text-center py-1.5">
        ✓ 연결 복구됨
      </div>
    );
  }

  return (
    <div className="bg-amber-900/40 border-b border-amber-800 text-amber-200 text-xs text-center py-1.5">
      ⚠ 실시간 끊김 — 재연결 중...
    </div>
  );
}
