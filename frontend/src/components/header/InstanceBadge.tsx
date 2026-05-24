import { useInstanceInfo } from "../../api/queries";

/** broker × env 조합별 색상. 인스턴스 혼동 방지가 1순위 목적이라 명도 대비 크게. */
const COLOR_BY_KEY: Record<string, string> = {
  "kis:vts": "bg-blue-900 text-blue-100 border-blue-700",
  "kis:real": "bg-emerald-900 text-emerald-100 border-emerald-700",
  "kiwoom:vts": "bg-purple-900 text-purple-100 border-purple-700",
  "kiwoom:real": "bg-amber-900 text-amber-100 border-amber-700",
};

export default function InstanceBadge() {
  const { data } = useInstanceInfo();
  if (!data) return null;
  const key = `${data.broker}:${data.env}`;
  const color = COLOR_BY_KEY[key] ?? "bg-zinc-800 text-zinc-200 border-zinc-700";
  return (
    <span
      className={`px-2 py-1 text-xs font-semibold rounded-md border ${color} whitespace-nowrap`}
      title={`active profile: ${data.broker}, ${data.env}`}
    >
      {data.label}
    </span>
  );
}
