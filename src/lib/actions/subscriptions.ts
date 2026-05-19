"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { stripeClient } from "@/lib/stripe/client";

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

const createSubscriptionSchema = z.object({
  customerId: z.string().min(1),
  stripeCustomerId: z.string().optional(),
  priceAmount: z.number().positive(),
  priceCurrency: z.string().min(3).max(3),
  interval: z.enum(["month", "year", "week"]).default("month"),
  intervalCount: z.number().int().positive().default(1),
  productName: z.string().min(1),
});

export type CreateSubscriptionInput = z.infer<typeof createSubscriptionSchema>;

export async function createSubscription(orgSlug: string, input: CreateSubscriptionInput) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = createSubscriptionSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const stripe = await stripeClient(orgId);
  if (!stripe) return { error: "Stripe is not configured for this organization" };

  const customer = await prisma.customer.findUnique({
    where: { id: parsed.data.customerId, organizationId: orgId },
    select: { email: true, companyName: true, firstName: true, lastName: true },
  });
  if (!customer) return { error: "Customer not found" };

  try {
    let stripeCustomerId = parsed.data.stripeCustomerId;

    if (!stripeCustomerId) {
      const stripeCustomer = await stripe.customers.create({
        email: customer.email ?? undefined,
        name:
          customer.companyName ??
          [customer.firstName, customer.lastName].filter(Boolean).join(" ") ??
          undefined,
        metadata: { crmCustomerId: parsed.data.customerId, organizationId: orgId },
      });
      stripeCustomerId = stripeCustomer.id;
    }

    const price = await stripe.prices.create({
      currency: parsed.data.priceCurrency.toLowerCase(),
      unit_amount: Math.round(parsed.data.priceAmount * 100),
      recurring: {
        interval: parsed.data.interval,
        interval_count: parsed.data.intervalCount,
      },
      product_data: { name: parsed.data.productName },
    });

    const stripeSub = await stripe.subscriptions.create({
      customer: stripeCustomerId,
      items: [{ price: price.id }],
      metadata: { crmCustomerId: parsed.data.customerId, organizationId: orgId },
      expand: ["items.data"],
    });

    const firstItem = stripeSub.items?.data[0];

    await prisma.subscription.create({
      data: {
        organizationId: orgId,
        customerId: parsed.data.customerId,
        stripeSubscriptionId: stripeSub.id,
        stripePriceId: price.id,
        status: stripeSub.status,
        currentPeriodStart: firstItem
          ? new Date(firstItem.current_period_start * 1000)
          : new Date(stripeSub.start_date * 1000),
        currentPeriodEnd: firstItem
          ? new Date(firstItem.current_period_end * 1000)
          : null,
      },
    });

    revalidatePath(`/${orgSlug}/customers/${parsed.data.customerId}`);
    return { success: true };
  } catch (err) {
    console.error("[Subscriptions] Create failed:", err);
    return { error: "Failed to create subscription. Check Stripe configuration." };
  }
}

export async function cancelSubscription(orgSlug: string, subscriptionId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const sub = await prisma.subscription.findUnique({
    where: { id: subscriptionId, organizationId: orgId },
  });
  if (!sub) return { error: "Subscription not found" };

  if (sub.stripeSubscriptionId) {
    const stripe = await stripeClient(orgId);
    if (stripe) {
      try {
        await stripe.subscriptions.cancel(sub.stripeSubscriptionId);
      } catch (err) {
        console.error("[Subscriptions] Stripe cancel failed:", err);
      }
    }
  }

  await prisma.subscription.update({
    where: { id: subscriptionId },
    data: { status: "canceled" },
  });

  revalidatePath(`/${orgSlug}/customers/${sub.customerId}`);
  return { success: true };
}

// ─── Internal: Stripe webhook sync ───────────────────────────────────────────

export async function syncSubscriptionFromStripe(
  orgId: string,
  stripeSubscriptionId: string,
  data: {
    status: string;
    current_period_start: number;
    current_period_end: number | null;
    stripePriceId?: string;
  }
) {
  await prisma.subscription.updateMany({
    where: { organizationId: orgId, stripeSubscriptionId },
    data: {
      status: data.status,
      currentPeriodStart: new Date(data.current_period_start * 1000),
      currentPeriodEnd: data.current_period_end
        ? new Date(data.current_period_end * 1000)
        : null,
      ...(data.stripePriceId && { stripePriceId: data.stripePriceId }),
    },
  });
}
