import { Prisma } from "@prisma/client";

/** Retries when concurrent number allocation produces the same value (rare for random, possible under load for sequences). */
export const MAX_DOC_NUMBER_ATTEMPTS = 25;

function p2002FieldTargets(error: Prisma.PrismaClientKnownRequestError): string[] {
  const raw = error.meta?.target;
  if (Array.isArray(raw)) return raw as string[];
  if (typeof raw === "string") return [raw];
  return [];
}

function isP2002OnFields(error: unknown, required: string[]): error is Prisma.PrismaClientKnownRequestError {
  if (!(error instanceof Prisma.PrismaClientKnownRequestError) || error.code !== "P2002") return false;
  const fields = p2002FieldTargets(error);
  if (fields.length === 0) return false;
  return required.every((f) => fields.includes(f));
}

/** Same org assigned the same invoice `number` (race or collision). */
export function isInvoiceOrgNumberUniqueConflict(error: unknown): boolean {
  return isP2002OnFields(error, ["organizationId", "number"]);
}

/** Same org assigned the same `receiptNumber` on two payments. */
export function isPaymentOrgReceiptNumberUniqueConflict(error: unknown): boolean {
  return isP2002OnFields(error, ["organizationId", "receiptNumber"]);
}

/** Dedupe Stripe webhook retries / double delivery (unchanged behaviour). */
export function isPaymentOrgStripeChargeUniqueConflict(error: unknown): boolean {
  return isP2002OnFields(error, ["organizationId", "stripeChargeId"]);
}
