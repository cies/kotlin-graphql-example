import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { TEMPLATE_KEYS, TEMPLATE_META } from "@/lib/email/template-keys";
import { ChevronRight } from "lucide-react";
import { EmailDesignPanel } from "@/components/templates/email-design-panel";
import { getCachedOrgEmailDesign } from "@/lib/settings/cached-org-settings";
import type { EmailDesign } from "@prisma/client";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export const metadata = { title: "Email Templates" };

export default async function TemplatesPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) redirect("/auth/login");

  const [customised, emailDesignRow] = await Promise.all([
    prisma.emailTemplate.findMany({
      where: { organizationId: org.id },
      select: { key: true, updatedAt: true },
    }),
    getCachedOrgEmailDesign(org.id),
  ]);
  const customisedSet = new Map(customised.map((c) => [c.key, c.updatedAt]));

  const emailDesign: EmailDesign = emailDesignRow.emailDesign;
  const emailAccentColor = emailDesignRow.emailAccentColor;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Email Templates</h1>
        <p className="text-sm text-[var(--muted-foreground)] mt-1">
          Customise the emails this CRM sends. Defaults apply when no customised version exists.
        </p>
      </div>

      <EmailDesignPanel
        orgSlug={orgSlug}
        initialDesign={emailDesign}
        initialAccentColor={emailAccentColor}
      />

      <Card>
        <CardHeader>
          <CardTitle>All templates</CardTitle>
        </CardHeader>
        <CardContent className="divide-y divide-[var(--border)]">
          {TEMPLATE_KEYS.map((key) => {
            const meta = TEMPLATE_META[key];
            const updatedAt = customisedSet.get(key);
            return (
              <Link
                key={key}
                href={`/${orgSlug}/templates/${encodeURIComponent(key)}`}
                className="flex items-start justify-between gap-4 py-4 hover:bg-[var(--muted)]/40 px-2 -mx-2 rounded-md transition-colors"
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <p className="font-medium">{meta.label}</p>
                    {updatedAt ? (
                      <Badge variant="info">Customised</Badge>
                    ) : (
                      <Badge variant="secondary">Default</Badge>
                    )}
                    <code className="text-xs text-[var(--muted-foreground)]">{key}</code>
                  </div>
                  <p className="text-sm text-[var(--muted-foreground)] mt-1">
                    {meta.description}
                  </p>
                </div>
                <ChevronRight className="h-5 w-5 text-[var(--muted-foreground)] shrink-0 mt-1" />
              </Link>
            );
          })}
        </CardContent>
      </Card>
    </div>
  );
}
