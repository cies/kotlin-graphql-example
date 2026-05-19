import type { PaymentMethod } from "@prisma/client";

export const RECEIPT_METHOD_LABELS: Record<PaymentMethod, string> = {
  STRIPE: "Card (Stripe)",
  BANK_TRANSFER: "Bank transfer",
  MANUAL: "Manual",
  CASH: "Cash",
};

export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/** Long date e.g. March 13, 2026 */
export function formatReceiptLongDate(date: Date): string {
  return new Date(date).toLocaleDateString("en-US", {
    month: "long",
    day: "numeric",
    year: "numeric",
  });
}

/** Period line under description e.g. Mar 13 – Apr 13, 2026 */
export function formatReceiptPeriodRange(from?: Date | string | null, to?: Date | string | null): string | null {
  if (!from && !to) return null;
  const a = from ? formatReceiptLongDate(new Date(from)) : "";
  const b = to ? formatReceiptLongDate(new Date(to)) : "";
  if (a && b) return `${a} – ${b}`;
  return a || b || null;
}

export function qtyLabelForReceiptLine(quantity: string | number, qtyType: string): string {
  const qtyNum = typeof quantity === "string" ? parseFloat(quantity) : quantity;
  if (qtyType === "HOURS" || qtyType === "QTY_HOURS") {
    return `${qtyNum.toFixed(2)} hrs`;
  }
  return qtyNum % 1 === 0 ? String(Math.round(qtyNum)) : String(qtyNum);
}

export function paymentMethodDisplay(
  method: PaymentMethod,
  stripeChargeId?: string | null,
  notes?: string | null
): string {
  const base = RECEIPT_METHOD_LABELS[method];
  if (method === "STRIPE" && stripeChargeId) {
    const short = stripeChargeId.length > 12 ? stripeChargeId.slice(-8) : stripeChargeId;
    return `${base} · ${short}`;
  }
  if (notes?.trim()) {
    return `${base} · ${notes.trim()}`;
  }
  return base;
}

/** Payment record column: bank/Stripe reference, memo, or "-" when empty - not the formal receipt number. */
export function paymentReferenceDisplay(
  stripeChargeId?: string | null,
  notes?: string | null
): string {
  if (stripeChargeId?.trim()) return stripeChargeId.trim();
  if (notes?.trim()) return notes.trim();
  return "-";
}

export function splitAddressBlock(block: string | undefined | null): string[] {
  if (!block?.trim()) return [];
  return block
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean);
}

/** When the payment has no stored receipt number (older data), derive a short display code from the paid time. */
function fallbackReceiptCodeFromPaidAt(paidAt: Date): string {
  const ts = new Date(paidAt).getTime();
  const a = String(Math.floor(ts / 10000) % 10000).padStart(4, "0");
  const b = String(ts % 10000).padStart(4, "0");
  return `${a}-${b}`;
}

/**
 * Receipt number shown on PDFs/emails: use the value saved on the payment (from `getNextReceiptNumber`, e.g. REC-2026-0001).
 * Only if that is missing (legacy rows) we synthesize a short code from the payment time - same behavior as before REF-* was introduced.
 */
export function resolveReceiptDisplayNumber(
  receiptNumber: string | null | undefined,
  _invoiceNumber: string,
  _paymentId: string | undefined,
  paidAt: Date
): string {
  if (receiptNumber?.trim()) return receiptNumber.trim();
  return fallbackReceiptCodeFromPaidAt(paidAt);
}
