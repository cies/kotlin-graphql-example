import { NextRequest, NextResponse } from "next/server";
import { platformStripe } from "@/lib/stripe/platform";
import { prisma } from "@/lib/db/prisma";
import type { OrgSubscriptionStatus } from "@prisma/client";
import type Stripe from "stripe";

export const runtime = "nodejs";

/**
 * Stripe sends webhooks with POST only. GET/HEAD return a clear response instead of 405.
 */
export function GET() {
  return new NextResponse(
    "Platform Stripe webhook - only POST requests with a valid Stripe-Signature are accepted. Configure this URL in the Stripe Dashboard for platform billing webhooks.",
    {
      status: 200,
      headers: {
        "Content-Type": "text/plain; charset=utf-8",
        "Cache-Control": "no-store",
      },
    }
  );
}

export function HEAD() {
  return new NextResponse(null, {
    status: 200,
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

function mapStatus(status: string): OrgSubscriptionStatus {
  switch (status) {
    case "active": return "ACTIVE";
    case "past_due": return "PAST_DUE";
    case "canceled": case "cancelled": return "CANCELED";
    case "trialing": return "TRIALING";
    case "unpaid": return "UNPAID";
    default: return "CANCELED";
  }
}

async function upsertSubscription(sub: Stripe.Subscription) {
  const orgId = sub.metadata?.organizationId;
  if (!orgId) return;

  const status = mapStatus(sub.status);
  const currentPeriodEnd = new Date((sub as Stripe.Subscription & { current_period_end: number }).current_period_end * 1000);

  await prisma.orgSubscription.upsert({
    where: { organizationId: orgId },
    update: {
      stripeSubscriptionId: sub.id,
      stripePriceId: (sub.items?.data[0]?.price?.id) ?? undefined,
      status,
      currentPeriodEnd,
      stripeCustomerId: typeof sub.customer === "string" ? sub.customer : sub.customer.id,
    },
    create: {
      organizationId: orgId,
      stripeSubscriptionId: sub.id,
      stripePriceId: (sub.items?.data[0]?.price?.id) ?? undefined,
      stripeCustomerId: typeof sub.customer === "string" ? sub.customer : sub.customer.id,
      status,
      currentPeriodEnd,
    },
  });
}

function subscriptionIdFromStripeInvoice(inv: Stripe.Invoice): string | null {
  const invWithSub = inv as Stripe.Invoice & {
    subscription?: string | Stripe.Subscription | null;
  };
  const top = invWithSub.subscription;
  if (typeof top === "string") return top;
  if (top && typeof top === "object") return (top as Stripe.Subscription).id;

  const subData =
    inv.parent?.type === "subscription_details" ? inv.parent.subscription_details : null;
  const sub = subData?.subscription;
  if (typeof sub === "string") return sub;
  if (sub && typeof sub === "object") return (sub as Stripe.Subscription).id;
  return null;
}

export async function POST(req: NextRequest) {
  const stripe = platformStripe();
  if (!stripe) {
    return NextResponse.json({ error: "Platform Stripe not configured" }, { status: 503 });
  }

  const body = await req.text();
  const sig = req.headers.get("stripe-signature");
  const webhookSecret = process.env.STRIPE_PLATFORM_WEBHOOK_SECRET;

  if (!sig || !webhookSecret) {
    return NextResponse.json({ error: "Missing signature or webhook secret" }, { status: 400 });
  }

  let event: Stripe.Event;
  try {
    event = stripe.webhooks.constructEvent(body, sig, webhookSecret);
  } catch (err) {
    console.error("[PlatformWebhook] Signature verification failed:", err);
    return NextResponse.json({ error: "Invalid signature" }, { status: 400 });
  }

  try {
    switch (event.type) {
      case "customer.subscription.created":
      case "customer.subscription.updated":
        await upsertSubscription(event.data.object as Stripe.Subscription);
        break;
      case "customer.subscription.deleted": {
        const sub = event.data.object as Stripe.Subscription;
        const orgId = sub.metadata?.organizationId;
        if (orgId) {
          await prisma.orgSubscription.updateMany({
            where: { organizationId: orgId },
            data: { status: "CANCELED" },
          });
        }
        break;
      }
      case "invoice.paid":
      case "invoice.payment_succeeded": {
        const inv = event.data.object as Stripe.Invoice;
        const subId = subscriptionIdFromStripeInvoice(inv);
        if (subId) {
          const sub = await stripe.subscriptions.retrieve(subId);
          await upsertSubscription(sub);
        }
        break;
      }
    }
  } catch (err) {
    console.error("[PlatformWebhook] Handler error:", err);
    return NextResponse.json({ error: "Handler failed" }, { status: 500 });
  }

  return NextResponse.json({ received: true });
}
