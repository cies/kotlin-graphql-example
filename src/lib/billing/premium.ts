import { prisma } from "@/lib/db/prisma";

async function hasActivePlatformSubscription(orgId: string): Promise<boolean> {
  let sub: { status: string; currentPeriodEnd: Date | null } | null = null;
  const orgSubscriptionDelegate = (prisma as unknown as Record<string, unknown>).orgSubscription as
    | { findUnique: (args: unknown) => Promise<{ status: string; currentPeriodEnd: Date | null } | null> }
    | undefined;

  if (orgSubscriptionDelegate?.findUnique) {
    sub = await orgSubscriptionDelegate.findUnique({
      where: { organizationId: orgId },
      select: { status: true, currentPeriodEnd: true },
    });
  } else {
    const rows = await prisma.$queryRaw<Array<{ status: string; currentPeriodEnd: Date | null }>>`
      SELECT "status", "currentPeriodEnd"
      FROM "OrgSubscription"
      WHERE "organizationId" = ${orgId}
      LIMIT 1
    `;
    sub = rows[0] ?? null;
  }

  if (!sub) return false;
  if (!["ACTIVE", "TRIALING"].includes(sub.status)) return false;
  if (sub.currentPeriodEnd && sub.currentPeriodEnd < new Date()) return false;
  return true;
}

/**
 * Returns true if the org has complimentary Premium (platform grant), or an active
 * or trialing platform subscription with a non-expired period end.
 */
export async function isOrgPremium(orgId: string): Promise<boolean> {
  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: { premiumComplimentary: true },
  });
  if (settings?.premiumComplimentary) return true;
  return hasActivePlatformSubscription(orgId);
}

/** Stripe platform subscription only (excludes complimentary grant). */
export async function isOrgPremiumFromSubscription(orgId: string): Promise<boolean> {
  return hasActivePlatformSubscription(orgId);
}
