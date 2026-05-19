"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { platformStripe } from "@/lib/stripe/platform";
import { isAbsoluteHttpUrl } from "@/lib/env/public-app-url";
import { resolvePublicAppOrigin } from "@/lib/env/resolve-public-app-url.server";

async function getOrgId(orgSlug: string): Promise<string | null> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") return null;

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) return null;

  const member = await prisma.organizationMember.findUnique({
    where: { organizationId_userId: { organizationId: org.id, userId: session.user.id } },
  });
  return member ? org.id : null;
}

export async function createPlatformCheckoutSession(
  orgSlug: string
): Promise<{ url?: string; error?: string }> {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const stripe = platformStripe();
  if (!stripe) return { error: "Premium subscriptions are not available yet" };

  const priceId = process.env.STRIPE_PLATFORM_PRICE_ID;
  if (!priceId) return { error: "Premium plan not configured" };

  const baseUrl = await resolvePublicAppOrigin();
  if (!baseUrl) {
    return { error: "Application URL is not configured (set NEXT_PUBLIC_APP_URL, APP_URL, or AUTH_URL)." };
  }

  const successUrl = `${baseUrl}/${orgSlug}/settings/billing?upgraded=1`;
  const cancelUrl = `${baseUrl}/${orgSlug}/settings/billing`;
  if (!isAbsoluteHttpUrl(successUrl) || !isAbsoluteHttpUrl(cancelUrl)) {
    return { error: "Invalid application URL; check NEXT_PUBLIC_APP_URL / APP_URL." };
  }

  const session = await stripe.checkout.sessions.create({
    mode: "subscription",
    payment_method_types: ["card"],
    line_items: [{ price: priceId, quantity: 1 }],
    metadata: { organizationId: orgId },
    subscription_data: { metadata: { organizationId: orgId } },
    success_url: successUrl,
    cancel_url: cancelUrl,
  });

  return { url: session.url ?? undefined };
}

export async function createPlatformBillingPortalSession(
  orgSlug: string
): Promise<{ url?: string; error?: string }> {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const stripe = platformStripe();
  if (!stripe) return { error: "Not configured" };

  const sub = await prisma.orgSubscription.findUnique({
    where: { organizationId: orgId },
    select: { stripeCustomerId: true },
  });

  if (!sub?.stripeCustomerId) return { error: "No subscription found" };

  const baseUrl = await resolvePublicAppOrigin();
  if (!baseUrl) {
    return { error: "Application URL is not configured (set NEXT_PUBLIC_APP_URL, APP_URL, or AUTH_URL)." };
  }

  const returnUrl = `${baseUrl}/${orgSlug}/settings/billing`;
  if (!isAbsoluteHttpUrl(returnUrl)) {
    return { error: "Invalid application URL; check NEXT_PUBLIC_APP_URL / APP_URL." };
  }

  const portal = await stripe.billingPortal.sessions.create({
    customer: sub.stripeCustomerId,
    return_url: returnUrl,
  });

  return { url: portal.url };
}
