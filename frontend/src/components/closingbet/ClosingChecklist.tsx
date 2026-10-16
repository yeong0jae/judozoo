import { LeadingConditions, type ConditionKit, type FilterResult } from "../common/detailParts";
import { GradeAvatar } from "./parts";
import type { Check, Grade } from "./model";

const pct = (v: number) => `${v > 0 ? "+" : ""}${Number.isInteger(v) ? v : v.toFixed(1)}%`;

const GROUPS = [
  { title: "고가를 형성했나요?", filters: ["고가 마감", "구간 신고가 (60거래일)"] },
  { title: "외국인·기관이 샀나요?", filters: ["외국인", "기관"] },
  { title: "마감 부근 시장과 종목 분위기가 좋았나요?", filters: ["마감 부근 수급", "애프터 버팀"] },
];

const SCALES: ConditionKit["scales"] = {
  "고가 마감": { min: -10, max: 0, pass: "above", label: pct, names: { 0: "고가" } },
  "구간 신고가 (60거래일)": { min: -20, max: 0, pass: "above", label: pct, names: { 0: "고점" } },
};

export default function ClosingChecklist({ code, name, grade, checks }: { code: string; name: string; grade: Grade; checks: Check[] }) {
  const results: FilterResult[] = checks.map((c) => ({
    filterName: c.title,
    criteriaDescription: c.criteria,
    actualValue: c.value,
    passed: c.ok,
    value: c.scale?.value ?? null,
    threshold: c.scale?.threshold ?? null,
  }));
  const kit: ConditionKit = {
    title: "종베 체크리스트",
    subtitle: (n) => `종가에 사서 내일 아침에 팔기 좋은 자리인지 ${n}가지로 봐요.`,
    groups: GROUPS,
    copy: Object.fromEntries(checks.map((c) => [c.title, { title: c.title, why: c.sentence }])),
    scales: SCALES,
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2 px-0.5">
        <GradeAvatar grade={grade} size={26} />
        <span className="truncate text-[15px] font-bold">{name}</span>
        <span className="shrink-0 text-xs text-zinc-500">{grade}등급</span>
      </div>
      <LeadingConditions key={code} results={results} kit={kit} />
    </div>
  );
}
