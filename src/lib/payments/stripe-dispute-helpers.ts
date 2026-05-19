/**
 * Stripe dispute status helpers (Charge / Dispute webhooks).
 * @see https://docs.stripe.com/api/disputes/object#dispute_object-status
 */

/** Merchant prevailed or inquiry closed without loss - CRM may clear linkage. */
export const DISPUTE_CLEARED_MERCHANT_OK = new Set(["won", "warning_closed"]);

/**
 * Funds lost to the cardholder / bank - invoice becomes CHARGEBACK.
 * `charge_refunded` is retained for API compatibility where it still appears.
 */
export const DISPUTE_LOST_CUSTOMER_WON = new Set(["lost", "charge_refunded"]);

export type PaymentDisputeFields = {
  stripeDisputeId: string | null;
  disputeStatus: string | null;
};

/** Use in invoice reconciliation: only finalized losses change invoice status. */
export function paymentIndicatesInvoiceChargeback(p: PaymentDisputeFields): boolean {
  if (!p.stripeDisputeId) return false;
  const s = (p.disputeStatus ?? "").trim();
  if (!s) return false;
  return DISPUTE_LOST_CUSTOMER_WON.has(s);
}

/**
 * Dispute is tracked on the payment and not yet cleared or finalized as lost.
 * (Includes needs_response, under_review, warning_*, prevented, and empty status.)
 */
export function paymentHasOpenStripeDispute(p: PaymentDisputeFields): boolean {
  if (!p.stripeDisputeId) return false;
  const s = (p.disputeStatus ?? "").trim();
  if (DISPUTE_CLEARED_MERCHANT_OK.has(s)) return false;
  if (DISPUTE_LOST_CUSTOMER_WON.has(s)) return false;
  return true;
}
