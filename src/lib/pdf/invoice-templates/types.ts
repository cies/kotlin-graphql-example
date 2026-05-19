import type { Currency, InvoicePdfFont, InvoiceStatus, InvoiceTemplate } from "@prisma/client";
import { formatCurrency as formatCurrencyValue, normalizeNumberFormatStyle, type NumberFormatStyle } from "@/lib/utils/format";

/** Non-empty lines from a multiline string (e.g. org company phone block). */
export function splitNonEmptyLines(text?: string | null): string[] {
  if (!text?.trim()) return [];
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

export interface InvoicePdfData {
  org: {
    name: string;
    address?: string | null;
    vat?: string | null;
    logoUrl?: string | null;
    /** Invoice / billing email (from org SMTP From when set) */
    billingEmail?: string | null;
    /** Multiline contact lines (phones, regional numbers), shown after address */
    phoneContact?: string | null;
  };
  invoice: {
    number: string;
    status: InvoiceStatus;
    displayStatus?: string;
    showStatus?: boolean;
    currency: Currency;
    issuedAt: Date;
    dueDate?: Date | null;
    subtotal: string;
    vat: string;
    vatRate?: number;
    total: string;
    amountPaid: string;
    discount?: string;
    discountType?: string;
    discountBeforeTax?: boolean;
    notes?: string | null;
    termsAndConditions?: string | null;
    template?: InvoiceTemplate | null;
    vatIncluded?: boolean;
    periodFrom?: Date | null;
    periodTo?: Date | null;
  };
  customer: {
    name: string;
    email?: string | null;
    address?: string | null;
    vat?: string | null;
    phone?: string | null;
  };
  lines: Array<{
    name: string;
    description?: string;
    quantity: string;
    qtyType?: string;
    unitPrice: string;
    total: string;
  }>;
  accentColor?: string;
  footerText?: string;
  logoPngBase64?: string;
  numberFormatStyle?: NumberFormatStyle;
  /** Org setting; defaults to HELVETICA in `generateInvoicePdf` if omitted. */
  invoicePdfFont?: InvoicePdfFont;
}

export function formatQtyDisplay(quantity: string, qtyType?: string): string {
  const num = parseFloat(quantity);
  if (qtyType === "HOURS" || qtyType === "QTY_HOURS") return `${num.toFixed(2)} hrs`;
  // Strip trailing zeros for plain Qty (1.00 → "1", 1.50 → "1.5", 2.75 → "2.75")
  return num % 1 === 0 ? String(Math.round(num)) : String(num);
}

export function qtyColLabel(qtyType?: string): string {
  if (qtyType === "HOURS") return "Hours";
  if (qtyType === "QTY_HOURS") return "Qty / Hrs";
  return "Qty";
}

/** Line item colours / sizes - aligned with receipt PDF (`lib/pdf/receipt-pdf.tsx`). */
export const INVOICE_LINE_ITEM_NAME_COLOR = "#111827";
/** Description under line title - slightly darker than before for print legibility. */
export const INVOICE_LINE_ITEM_DESC_COLOR = "#475569";
/** Line title (templates), qty, unit price - closer to body text than muted grey. */
export const INVOICE_LINE_ITEM_QTY_PRICE_COLOR = "#374151";
export const INVOICE_LINE_ITEM_NAME_SIZE = 9;
export const INVOICE_LINE_ITEM_QTY_PRICE_SIZE = 9;
/** Line total: slightly heavier than qty/price (larger); receipt uses full bold. */
export const INVOICE_LINE_ITEM_TOTAL_SIZE = 10;

export function formatCurrency(amount: string, currency: Currency): string {
  return formatCurrencyValue(amount, currency);
}

export function formatCurrencyWithStyle(amount: string, currency: Currency, style?: NumberFormatStyle): string {
  return formatCurrencyValue(amount, currency, normalizeNumberFormatStyle(style));
}

/** Amount + ISO code for grand due row (e.g. `$336.25 USD`). */
export function formatCurrencyWithStyleAndCode(
  amount: string,
  currency: Currency,
  style?: NumberFormatStyle
): string {
  return `${formatCurrencyWithStyle(amount, currency, style)} ${currency}`;
}

export function formatDiscountLabel(discountType?: string, discountValue?: string, discountBeforeTax?: boolean): string {
  if (!discountType || discountType === "NONE") return "Discount";
  const suffix = discountBeforeTax ? " (before tax)" : " (after tax)";
  if (discountType === "PERCENTAGE") return `Discount (${parseFloat(discountValue ?? "0").toFixed(1)}%${suffix})`;
  return `Discount (fixed${suffix})`;
}

export function formatVatLabel(vatRate?: number, vatIncluded?: boolean): string {
  const pct = vatRate !== undefined ? (vatRate * 100).toFixed(1) : "0.0";
  const suffix = vatIncluded ? " incl." : "";
  return `VAT (${pct}%${suffix})`;
}

export function formatDate(date: Date | null | undefined): string {
  if (!date) return "-";
  return new Date(date).toLocaleDateString("en-GB", {
    day: "2-digit", month: "short", year: "numeric",
  });
}

export const STATUS_COLORS: Record<string, string> = {
  DRAFT: "#e5e7eb",
  SENT: "#dbeafe",
  PARTIAL: "#fef9c3",
  PAID: "#dcfce7",
  OVERDUE: "#fee2e2",
  VOID: "#f3f4f6",
  REFUNDED: "#fed7aa",
  CHARGEBACK: "#fecaca",
};

export function getInvoiceDisplayStatus(
  status: InvoiceStatus,
  displayStatus?: string
): string {
  if (displayStatus && displayStatus.trim().length > 0) return displayStatus.trim();
  if (status === "SENT") return "UNPAID";
  return status;
}
