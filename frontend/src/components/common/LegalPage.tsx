import type { ReactNode } from "react";

/** 약관·처리방침 공용 레이아웃. 읽는 문서라 본문 폭을 좁게 잡는다. */
export default function LegalPage({
  title,
  effectiveDate,
  children,
}: {
  title: string;
  effectiveDate: string;
  children: ReactNode;
}) {
  return (
    <article className="max-w-3xl mx-auto space-y-6 pb-16">
      <header className="space-y-1">
        <h1 className="text-xl font-bold text-zinc-100">{title}</h1>
        <p className="text-xs text-zinc-500">시행일 {effectiveDate}</p>
      </header>
      <div className="space-y-6 text-sm leading-relaxed text-zinc-300 [&_h2]:text-base [&_h2]:font-semibold [&_h2]:text-zinc-100 [&_h2]:mt-8 [&_h2]:mb-2 [&_ul]:list-disc [&_ul]:pl-5 [&_ul]:space-y-1 [&_p]:text-zinc-400">
        {children}
      </div>
    </article>
  );
}
