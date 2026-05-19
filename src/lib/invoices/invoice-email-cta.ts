import type { InvoiceStatus, PaymentMethod } from "@prisma/client";

/**
 * Primary button label in customer invoice emails (`invoice.sent`), aligned with public invoice pay options.
 */
export function invoiceEmailPrimaryCtaLabel(params: {
  invoiceStatus: InvoiceStatus;
  paymentMethod: PaymentMethod | null;
  stripeConfigured: boolean;
  amountDueNum: number;
}): string {
  const { invoiceStatus, paymentMethod, stripeConfigured, amountDueNum } = params;

  if (
    invoiceStatus === "VOID" ||
    invoiceStatus === "PAID" ||
    invoiceStatus === "REFUNDED" ||
    invoiceStatus === "CHARGEBACK"
  ) {
    return "View invoice";
  }

  const due = Number.isFinite(amountDueNum) ? amountDueNum : 0;
  if (due <= 0) {
    return "View invoice";
  }

  const bankLike =
    paymentMethod === "BANK_TRANSFER" ||
    paymentMethod === "MANUAL" ||
    paymentMethod === "CASH";

  const stripePrimary =
    stripeConfigured && (paymentMethod === null || paymentMethod === "STRIPE");

  if (bankLike) {
    return "View invoice and payment options";
  }
  if (stripePrimary) {
    return "Pay by Stripe";
  }

  return "View invoice and payment options";
}
