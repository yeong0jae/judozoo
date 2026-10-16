import mark from "./mark.json";

export default function Logo({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox={mark.viewBox} aria-hidden>
      <path
        d={mark.dot.d}
        fill="none"
        stroke="currentColor"
        strokeWidth={mark.dot.strokeWidth}
        strokeLinecap="round"
      />
      <rect
        x={mark.body.x}
        y={mark.body.y}
        width={mark.body.width}
        height={mark.body.height}
        rx={mark.body.rx}
        fill={mark.up}
      />
      <path
        d={mark.hook.d}
        fill="none"
        stroke="currentColor"
        strokeWidth={mark.hook.strokeWidth}
        strokeLinecap="round"
      />
    </svg>
  );
}
