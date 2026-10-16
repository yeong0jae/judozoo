import { useEffect, useRef, useState, type ReactNode } from "react";

export default function FlashOnChange({
  value,
  children,
  duration = 300,
}: {
  value: number;
  children: ReactNode;
  duration?: number;
}) {
  const [flash, setFlash] = useState<"up" | "down" | null>(null);
  const prev = useRef(value);

  useEffect(() => {
    if (value > prev.current) setFlash("up");
    else if (value < prev.current) setFlash("down");
    prev.current = value;
  }, [value]);

  useEffect(() => {
    if (!flash) return;
    const t = setTimeout(() => setFlash(null), duration);
    return () => clearTimeout(t);
  }, [flash, duration]);

  const cls =
    flash === "up"
      ? "bg-rose-500/20"
      : flash === "down"
        ? "bg-blue-500/20"
        : "";
  return (
    <span className={`inline-block transition-colors duration-300 px-1 rounded ${cls}`}>
      {children}
    </span>
  );
}
