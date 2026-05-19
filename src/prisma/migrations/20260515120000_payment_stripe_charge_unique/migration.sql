-- Stripe charge id is idempotency key for webhook retries (per org). PostgreSQL allows multiple NULLs in UNIQUE.
CREATE UNIQUE INDEX "Payment_organizationId_stripeChargeId_key" ON "Payment"("organizationId", "stripeChargeId");
