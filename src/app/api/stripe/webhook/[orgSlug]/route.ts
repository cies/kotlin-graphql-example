import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/db/prisma";
import { stripeClient } from "@/lib/stripe/client";
import { syncSubscriptionFromStripe } from "@/lib/actions/subscriptions";

import {
  fulfillTenantInvoiceCheckoutSession,
  resolveChargeIdForPaymentIntent,
  stripeMetadataOrgMatches,
} from "@/lib/stripe/invoice-webhook-fulfill";
import {
  applyStripeChargeRefunded,
  applyStripeDispute,
  handleStripePaymentSucceeded,
} from "@/lib/actions/payments";

import type Stripe from "stripe";

export const runtime = "nodejs";

/**
 * Stripe sends webhooks with POST only. A plain GET avoids 405 when someone
 * opens the URL in a browser; we do not touch the DB here (no org enumeration).
 */
export function GET() {
  return new NextResponse(
    "Stripe webhook endpoint - only POST requests with a valid Stripe-Signature are accepted. Add this URL in the Stripe Dashboard under Webhooks.",
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

export async function POST(
  req: NextRequest,
  { params }: { params: Promise<{ orgSlug: string }> }
) {
  const { orgSlug } = await params;

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true, settings: true },
  });

  if (!org) {
    return NextResponse.json({ error: "Organization not found" }, { status: 404 });
  }

  const webhookSecret = org.settings?.stripeWebhookSecret;
  if (!webhookSecret) {
    return NextResponse.json(
      { error: "Webhook secret not configured", code: "missing_webhook_secret" },
      { status: 400 }
    );
  }

  const stripe = await stripeClient(org.id);
  if (!stripe) {
    return NextResponse.json(
      { error: "Stripe not configured", code: "missing_stripe_keys" },
      { status: 400 }
    );
  }

  const body = await req.text();
  const signature = req.headers.get("stripe-signature");

  if (!signature) {
    return NextResponse.json(
      { error: "Missing stripe-signature header", code: "missing_signature" },
      { status: 400 }
    );
  }

  let event: Stripe.Event;
  try {
    event = stripe.webhooks.constructEvent(body, signature, webhookSecret);
  } catch (err) {
    console.error("[Stripe Webhook] Signature verification failed:", err);
    return NextResponse.json(
      { error: "Invalid signature", code: "invalid_signature" },
      { status: 400 }
    );
  }

  try {
    switch (event.type) {
      case "payment_intent.succeeded": {
        const pi = event.data.object as Stripe.PaymentIntent;
        const invoiceId = pi.metadata?.invoiceId?.trim();
        if (!invoiceId) break;

        if (!stripeMetadataOrgMatches(pi.metadata, org.id)) {
          console.warn("[Stripe Webhook] payment_intent metadata organization mismatch; skipping CRM apply");
          break;
        }

        const chargeId = await resolveChargeIdForPaymentIntent(stripe, pi);
        if (!chargeId) {
          console.warn("[Stripe Webhook] payment_intent missing charge id; skipping CRM apply");
          break;
        }

        const amountCents =
          pi.amount_received > 0 ? pi.amount_received : pi.status === "succeeded" ? pi.amount : pi.amount_received;

        await handleStripePaymentSucceeded(
          org.id,
          invoiceId,
          chargeId,
          amountCents,
          pi.currency
        );
        break;
      }

      case "checkout.session.completed":
      case "checkout.session.async_payment_succeeded": {
        const session = event.data.object as Stripe.Checkout.Session;
        await fulfillTenantInvoiceCheckoutSession(org.id, stripe, session);
        break;
      }

      case "customer.subscription.updated":
      case "customer.subscription.deleted": {
        const sub = event.data.object as Stripe.Subscription;
        const firstItem = sub.items?.data[0];
        await syncSubscriptionFromStripe(org.id, sub.id, {
          status: sub.status,
          current_period_start: firstItem?.current_period_start ?? sub.start_date,
          current_period_end: firstItem?.current_period_end ?? null,
          stripePriceId: firstItem?.price?.id,
        });
        break;
      }

      case "invoice.paid":
      case "invoice.payment_succeeded": {
        const stripeInvoice = event.data.object as Stripe.Invoice;
        // In Stripe v22, subscription is accessed via invoice.parent.subscription_details
        const subData = stripeInvoice.parent?.type === "subscription_details"
          ? stripeInvoice.parent.subscription_details
          : null;
        const subId = subData?.subscription
          ? typeof subData.subscription === "string"
            ? subData.subscription
            : (subData.subscription as Stripe.Subscription).id
          : null;

        if (subId) {
          const stripeSub = await stripe.subscriptions.retrieve(subId, {
            expand: ["items.data"],
          });
          const firstItem = stripeSub.items?.data[0];
          await syncSubscriptionFromStripe(org.id, subId, {
            status: stripeSub.status,
            current_period_start: firstItem?.current_period_start ?? stripeSub.start_date,
            current_period_end: firstItem?.current_period_end ?? null,
          });
        }
        break;
      }

      case "invoice.payment_failed": {
        const stripeInvoice = event.data.object as Stripe.Invoice;
        const subData = stripeInvoice.parent?.type === "subscription_details"
          ? stripeInvoice.parent.subscription_details
          : null;
        const subId = subData?.subscription
          ? typeof subData.subscription === "string"
            ? subData.subscription
            : (subData.subscription as Stripe.Subscription).id
          : null;

        if (subId) {
          await prisma.subscription.updateMany({
            where: { organizationId: org.id, stripeSubscriptionId: subId },
            data: { status: "past_due" },
          });
        }
        break;
      }

      case "charge.refunded": {
        const charge = event.data.object as Stripe.Charge;
        await applyStripeChargeRefunded(org.id, charge);
        break;
      }

      case "charge.dispute.created":
      case "charge.dispute.updated":
      case "charge.dispute.closed": {
        const dispute = event.data.object as Stripe.Dispute;
        await applyStripeDispute(org.id, dispute, { stripeEventType: event.type });
        break;
      }

      default:
        break;
    }

    return NextResponse.json({ received: true });
  } catch (err) {
    console.error("[Stripe Webhook] Handler error:", err);
    return NextResponse.json({ error: "Webhook handler failed" }, { status: 500 });
  }
}
