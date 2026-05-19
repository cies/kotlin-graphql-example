import type Stripe from "stripe";
import { handleStripePaymentSucceeded } from "@/lib/actions/payments";

function metaValue(meta: Stripe.Metadata | null, key: string): string | undefined {
  const v = meta?.[key];
  if (v === undefined || v === null) return undefined;
  const s = String(v).trim();
  return s || undefined;
}

export function stripeMetadataOrgMatches(meta: Stripe.Metadata | null, orgId: string): boolean {
  const fromMeta = metaValue(meta, "organizationId");
  return !!fromMeta && fromMeta === orgId;
}

/** Latest charge id for a succeeded PaymentIntent (webhook PI payloads are sometimes minimal). */
export async function resolveChargeIdForPaymentIntent(
  stripe: Stripe,
  pi: Stripe.PaymentIntent
): Promise<string> {
  const direct =
    typeof pi.latest_charge === "string"
      ? pi.latest_charge
      : (pi.latest_charge as Stripe.Charge | null)?.id ?? "";
  if (direct) return direct;

  const expanded = await stripe.paymentIntents.retrieve(pi.id, {
    expand: ["latest_charge"],
  });
  const lc = expanded.latest_charge;
  if (typeof lc === "string" && lc) return lc;
  const id = (lc as Stripe.Charge | null)?.id;
  if (id) return id;

  const listed = await stripe.charges.list({ payment_intent: pi.id, limit: 1 });
  const first = listed.data[0]?.id;
  return first ?? "";
}

function paymentAmountReceivedCents(pi: Stripe.PaymentIntent): number {
  if (pi.amount_received > 0) return pi.amount_received;
  if (pi.status === "succeeded" && pi.amount > 0) return pi.amount;
  return pi.amount_received;
}

/**
 * Apply CRM payment for tenant invoice Checkout (mode=payment, metadata invoiceId).
 * Retrieves the Session from the API so `payment_intent` and `metadata` are reliable.
 */
export async function fulfillTenantInvoiceCheckoutSession(
  orgId: string,
  stripe: Stripe,
  sessionLike: Pick<Stripe.Checkout.Session, "id">
): Promise<void> {
  const session = await stripe.checkout.sessions.retrieve(sessionLike.id, {
    expand: ["payment_intent.latest_charge"],
  });

  if (session.mode !== "payment") return;

  if (!stripeMetadataOrgMatches(session.metadata, orgId)) {
    console.warn(
      "[Stripe Webhook] checkout session metadata organization mismatch; skipping CRM invoice apply"
    );
    return;
  }

  const invoiceId = metaValue(session.metadata, "invoiceId");
  if (!invoiceId) {
    if (session.mode === "payment") {
      console.warn(
        `[Stripe Webhook] checkout session ${session.id} is mode=payment but missing invoiceId metadata; skipping`
      );
    }
    return;
  }

  if (session.payment_status !== "paid") {
    console.warn(
      `[Stripe Webhook] checkout session ${session.id} payment_status=${session.payment_status}; skipping CRM apply (async flow may retry)`
    );
    return;
  }

  const piRef = session.payment_intent;
  if (!piRef) {
    console.warn(`[Stripe Webhook] checkout session ${session.id} has no payment_intent after retrieve`);
    return;
  }

  let pi: Stripe.PaymentIntent;
  if (typeof piRef === "string") {
    pi = await stripe.paymentIntents.retrieve(piRef, { expand: ["latest_charge"] });
  } else {
    pi = piRef as Stripe.PaymentIntent;
    if (!pi.latest_charge && pi.status === "succeeded") {
      pi = await stripe.paymentIntents.retrieve(pi.id, { expand: ["latest_charge"] });
    }
  }

  const chargeId = await resolveChargeIdForPaymentIntent(stripe, pi);
  if (!chargeId) {
    console.warn("[Stripe Webhook] could not resolve charge id for checkout session; skipping CRM apply");
    return;
  }

  const amountCents = paymentAmountReceivedCents(pi);
  if (amountCents <= 0) {
    console.warn("[Stripe Webhook] payment intent has zero amount; skipping CRM apply");
    return;
  }

  await handleStripePaymentSucceeded(orgId, invoiceId, chargeId, amountCents, pi.currency);
}
