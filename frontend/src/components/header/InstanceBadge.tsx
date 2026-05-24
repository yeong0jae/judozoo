import { useInstanceInfo } from "../../api/queries";

/** broker × env 조합별 dot/text 색. 인스턴스 혼동 방지가 1순위. */
const META: Record<string, { dot: string; text: string }> = {
  "kis:vts": { dot: "bg-blue-500", text: "text-blue-700" },
  "kis:real": { dot: "bg-emerald-500", text: "text-emerald-800" },
  "kiwoom:vts": { dot: "bg-purple-500", text: "text-purple-700" },
  "kiwoom:real": { dot: "bg-amber-500", text: "text-amber-700" },
};

export default function InstanceBadge() {
  const { data } = useInstanceInfo();
  if (!data) return null;
  const key = `${data.broker}:${data.env}`;
  const meta = META[key] ?? { dot: "bg-zinc-500", text: "text-zinc-300" };
  return (
    <span
      className={`flex items-center gap-1.5 px-2.5 py-1 rounded text-xs ${meta.text}`}
      title={`active profile: ${data.broker}, ${data.env}`}
    >
      <span className={`w-2 h-2 rounded-full ${meta.dot}`} />
      {data.label}
    </span>
  );
}
