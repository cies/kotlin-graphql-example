"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { revalidatePath } from "next/cache";
import { isPlatformAdminEmail } from "@/lib/platform-admin";
import { logAudit } from "@/lib/audit/log";
import { isOrgPremiumFromSubscription } from "@/lib/billing/premium";

export type PlatformOrgRow = {
  id: string;
  name: string;
  slug: string;
  premiumComplimentary: boolean;
  subscriptionPremium: boolean;
};

export type QuotationRequestRow = {
  id: string;
  name: string;
  email: string;
  company: string | null;
  message: string;
  createdAt: Date;
};

export async function listOrganizationsForPlatformAdmin(): Promise<
  { organizations: PlatformOrgRow[] } | { error: string }
> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") {
    return { error: "Unauthorized" };
  }
  if (!isPlatformAdminEmail(session.user.email)) {
    return { error: "Unauthorized" };
  }

  const orgs = await prisma.organization.findMany({
    orderBy: { name: "asc" },
    select: {
      id: true,
      name: true,
      slug: true,
      settings: { select: { premiumComplimentary: true } },
    },
  });

  const rows: PlatformOrgRow[] = [];
  for (const o of orgs) {
    const subscriptionPremium = await isOrgPremiumFromSubscription(o.id);
    rows.push({
      id: o.id,
      name: o.name,
      slug: o.slug,
      premiumComplimentary: o.settings?.premiumComplimentary ?? false,
      subscriptionPremium,
    });
  }

  return { organizations: rows };
}

export async function listQuotationRequestsForPlatformAdmin(): Promise<
  { requests: QuotationRequestRow[] } | { error: string }
> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") {
    return { error: "Unauthorized" };
  }
  if (!isPlatformAdminEmail(session.user.email)) {
    return { error: "Unauthorized" };
  }

  const rows = await prisma.quotationRequest.findMany({
    orderBy: { createdAt: "desc" },
    take: 500,
  });

  return { requests: rows };
}

export async function setOrgPremiumComplimentary(
  organizationId: string,
  enabled: boolean
): Promise<{ success: true } | { error: string }> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") {
    return { error: "Unauthorized" };
  }
  if (!isPlatformAdminEmail(session.user.email)) {
    return { error: "Unauthorized" };
  }

  const org = await prisma.organization.findUnique({
    where: { id: organizationId },
    select: { id: true, slug: true },
  });
  if (!org) return { error: "Organization not found" };

  await prisma.orgSettings.upsert({
    where: { organizationId: org.id },
    create: {
      organizationId: org.id,
      premiumComplimentary: enabled,
    },
    update: { premiumComplimentary: enabled },
  });

  await logAudit({
    organizationId: org.id,
    action: "UPDATE",
    entityType: "SETTINGS",
    entityId: org.id,
    metadata: {
      premiumComplimentary: enabled,
      platformAdmin: session.user.email ?? session.user.id,
    },
  });

  revalidatePath("/admin");
  revalidatePath(`/${org.slug}/settings`);
  revalidatePath(`/${org.slug}/settings/billing`);

  return { success: true };
}
