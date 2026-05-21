import { useEffect, useRef, useState } from "react";
import { useStompState } from "../../ws/StompProvider";

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
      <div className="bg-emerald-50 border-b border-emerald-200 text-emerald-800 text-xs text-center py-1.5">
        ✓ 연결 복구됨
      </div>
    );
  }

  return (
    <div className="bg-amber-50 border-b border-amber-200 text-amber-800 text-xs text-center py-1.5">
      ⚠ 실시간 끊김 — 재연결 중...
    </div>
  );
}
