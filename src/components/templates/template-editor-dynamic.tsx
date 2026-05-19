"use client";

import dynamic from "next/dynamic";
import { Loader2 } from "lucide-react";
import type { TemplateEditorProps } from "./template-editor";

const TemplateEditorLazy = dynamic(
  () => import("./template-editor").then((m) => ({ default: m.TemplateEditor })),
  {
    ssr: false,
    loading: () => (
      <div className="flex items-center justify-center gap-2 p-12 text-[var(--muted-foreground)]">
        <Loader2 className="h-6 w-6 animate-spin" aria-hidden />
        Loading editor…
      </div>
    ),
  }
);

export function TemplateEditorDynamic(props: TemplateEditorProps) {
  return <TemplateEditorLazy {...props} />;
}
