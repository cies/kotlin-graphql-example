/**
 * Platform Stripe client for CRM SaaS subscriptions.
 * Uses STRIPE_PLATFORM_SECRET_KEY env var (not per-org OrgSettings).
 */
import Stripe from "stripe";

let _client: Stripe | null = null;

export function platformStripe(): Stripe | null {
  const key = process.env.STRIPE_PLATFORM_SECRET_KEY;
  if (!key) return null;
  if (!_client) {
    _client = new Stripe(key, { apiVersion: "2026-04-22.dahlia" });
  }
  return _client;
}
