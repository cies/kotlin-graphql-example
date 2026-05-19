import { prisma } from "@/lib/db/prisma";
import { buildReceiptPdfDataFromPayment, type ReceiptPdfData } from "@/lib/pdf/receipt-pdf";

export async function getPaymentWithReceiptContext(paymentId: string) {
  return prisma.payment.findUnique({
    where: { id: paymentId },
    include: {
      invoice: {
        include: {
          customer: true,
          lines: { orderBy: { sortOrder: "asc" } },
          organization: { include: { settings: true } },
        },
      },
    },
  });
}

export type PaymentWithReceiptContext = NonNullable<
  Awaited<ReturnType<typeof getPaymentWithReceiptContext>>
>;

export function toReceiptPdfData(payment: PaymentWithReceiptContext): ReceiptPdfData {
  const inv = payment.invoice;
  const org = inv.organization;
  return buildReceiptPdfDataFromPayment({
    payment,
    invoice: {
      number: inv.number,
      currency: inv.currency,
      subtotal: inv.subtotal,
      vat: inv.vat,
      total: inv.total,
      periodFrom: inv.periodFrom,
      periodTo: inv.periodTo,
      customer: inv.customer,
      lines: inv.lines,
    },
    org: { name: org.name, settings: org.settings },
  });
}
