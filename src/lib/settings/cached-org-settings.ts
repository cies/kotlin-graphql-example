import { unstable_cache } from "next/cache";
import { prisma } from "@/lib/db/prisma";
import type { EmailDesign } from "@prisma/client";

/** Use with `revalidateTag` after any `OrgSettings` mutation for this org. */
export function orgSettingsCacheTag(organizationId: string): string {
  return `org:${organizationId}:settings`;
}

export async function getCachedOrgNumberFormatStyle(
  organizationId: string
): Promise<string | null> {
  return unstable_cache(
    async () => {
      const s = await prisma.orgSettings.findUnique({
        where: { organizationId },
        select: { numberFormatStyle: true },
      });
      return s?.numberFormatStyle ?? null;
    },
    ["org-number-format-style", organizationId],
    { revalidate: 120, tags: [orgSettingsCacheTag(organizationId)] }
  )();
}

export async function getCachedOrgEmailDesign(organizationId: string): Promise<{
  emailDesign: EmailDesign;
  emailAccentColor: string | null;
}> {
  return unstable_cache(
    async () => {
      const s = await prisma.orgSettings.findUnique({
        where: { organizationId },
        select: { emailDesign: true, emailAccentColor: true },
      });
      return {
        emailDesign: (s?.emailDesign ?? "MODERN_MINIMAL") as EmailDesign,
        emailAccentColor: s?.emailAccentColor ?? null,
      };
    },
    ["org-email-design", organizationId],
    { revalidate: 120, tags: [orgSettingsCacheTag(organizationId)] }
  )();
}
