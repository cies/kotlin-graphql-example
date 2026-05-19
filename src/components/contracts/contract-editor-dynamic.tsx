"use client";

import dynamic from "next/dynamic";
import { Loader2 } from "lucide-react";

const ContractEditorLazy = dynamic(
  () => import("./contract-editor").then((m) => ({ default: m.ContractEditor })),
  {
    ssr: false,
    loading: () => (
      <div className="flex min-h-[200px] items-center justify-center gap-2 rounded-lg border border-[var(--border)] bg-[var(--muted)]/30 text-[var(--muted-foreground)]">
        <Loader2 className="h-6 w-6 animate-spin" aria-hidden />
        Loading editor…
      </div>
    ),
  }
);

interface Props {
  value: string;
  onChange: (html: string) => void;
}

export function ContractEditorDynamic(props: Props) {
  return <ContractEditorLazy {...props} />;
}
