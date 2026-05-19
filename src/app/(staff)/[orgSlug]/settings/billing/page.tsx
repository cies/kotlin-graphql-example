import { notFound } from "next/navigation";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { isOrgPremium, isOrgPremiumFromSubscription } from "@/lib/billing/premium";
import { BillingPageClient } from "@/components/settings/billing-page-client";

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<{ upgraded?: string }>;
}

export default async function BillingPage({ params, searchParams }: Props) {
  const { orgSlug } = await params;
  const { upgraded } = await searchParams;

  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") notFound();

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true, name: true },
  });
  if (!org) notFound();

  const member = await prisma.organizationMember.findUnique({
    where: { organizationId_userId: { organizationId: org.id, userId: session.user.id } },
  });
  if (!member) notFound();

  const [premium, subscription, canManageStripe, complimentary] = await Promise.all([
    isOrgPremium(org.id),
    prisma.orgSubscription.findUnique({
      where: { organizationId: org.id },
      select: { status: true, currentPeriodEnd: true, stripePriceId: true },
    }),
    isOrgPremiumFromSubscription(org.id),
    prisma.orgSettings
      .findUnique({
        where: { organizationId: org.id },
        select: { premiumComplimentary: true },
      })
      .then((s) => s?.premiumComplimentary ?? false),
  ]);

  return (
    <div className="max-w-2xl">
      <div className="mb-8">
        <h1 className="text-2xl font-bold">Subscription &amp; billing</h1>
        <p className="text-[var(--muted-foreground)] mt-1">
          Manage your CRM premium subscription for invoice personalisation and advanced features.
        </p>
      </div>
      <BillingPageClient
        orgSlug={orgSlug}
        isPremium={premium}
        premiumComplimentary={complimentary}
        canManageStripe={canManageStripe}
        subscription={
          subscription
            ? {
                status: subscription.status,
                currentPeriodEnd: subscription.currentPeriodEnd?.toISOString() ?? null,
              }
            : null
        }
        justUpgraded={upgraded === "1"}
      />
    </div>
  );
}
