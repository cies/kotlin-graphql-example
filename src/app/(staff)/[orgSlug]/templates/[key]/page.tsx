import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect, notFound } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { ChevronLeft } from "lucide-react";
import { TEMPLATE_META, TEMPLATE_KEYS } from "@/lib/email/template-keys";
import { getTemplateForEditor } from "@/lib/actions/templates";
import { TemplateEditorDynamic } from "@/components/templates/template-editor-dynamic";
import { getCachedOrgEmailDesign } from "@/lib/settings/cached-org-settings";
import type { EmailDesign } from "@prisma/client";

interface Props {
  params: Promise<{ orgSlug: string; key: string }>;
}

export const metadata = { title: "Edit Template" };

export default async function TemplateEditorPage({ params }: Props) {
  const { orgSlug, key: rawKey } = await params;
  const key = decodeURIComponent(rawKey);

  if (!TEMPLATE_KEYS.includes(key as (typeof TEMPLATE_KEYS)[number])) {
    notFound();
  }

  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) redirect("/auth/login");

  const [tpl, emailDesignRow] = await Promise.all([
    getTemplateForEditor(orgSlug, key),
    getCachedOrgEmailDesign(org.id),
  ]);

  if (!tpl) notFound();

  const meta = TEMPLATE_META[key as (typeof TEMPLATE_KEYS)[number]];
  const emailDesign: EmailDesign = emailDesignRow.emailDesign;
  const emailAccentColor = emailDesignRow.emailAccentColor;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="sm" asChild>
          <Link href={`/${orgSlug}/templates`}>
            <ChevronLeft className="h-4 w-4" />
            Back to templates
          </Link>
        </Button>
      </div>

      <div>
        <h1 className="text-2xl font-bold">{meta.label}</h1>
        <p className="text-sm text-[var(--muted-foreground)] mt-1">{meta.description}</p>
        <code className="text-xs text-[var(--muted-foreground)] mt-2 inline-block">{key}</code>
      </div>

      <TemplateEditorDynamic
        orgSlug={orgSlug}
        templateKey={key}
        initialSubject={tpl.subject}
        initialBody={tpl.bodyMjml}
        defaultSubject={tpl.defaultSubject}
        defaultBody={tpl.defaultBody}
        isCustomised={tpl.isCustomised}
        availableTags={meta.availableTags}
        sampleVars={tpl.sampleVars}
        initialEmailDesign={emailDesign}
        initialAccentColor={emailAccentColor}
      />
    </div>
  );
}
