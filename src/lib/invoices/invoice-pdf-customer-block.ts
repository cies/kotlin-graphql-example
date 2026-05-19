import type { Customer } from "@prisma/client";
import type { InvoicePdfData } from "@/lib/pdf/invoice-templates/types";
import { formatBillingAddress } from "@/lib/utils/format";

/** Pure helper - not a Server Action (must live outside `"use server"` modules). */
export function invoicePdfCustomerBlock(
  customer: Customer,
  customerName: string
): InvoicePdfData["customer"] {
  return {
    name: customerName,
    email: customer.hideEmailOnInvoice ? null : customer.email,
    vat: customer.vat,
    address: formatBillingAddress(customer.billingAddress),
    phone: customer.hidePhoneOnInvoice ? null : customer.phone,
  };
}
