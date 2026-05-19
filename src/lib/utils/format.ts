import { Currency } from "@prisma/client";
import { absoluteAppUrl } from "@/lib/env/public-app-url";

export type NumberFormatStyle = "COMMA_DOT" | "DOT_COMMA";

export function normalizeNumberFormatStyle(value: string | null | undefined): NumberFormatStyle {
  return value === "DOT_COMMA" ? "DOT_COMMA" : "COMMA_DOT";
}

function localeForStyle(style: NumberFormatStyle): string {
  return style === "DOT_COMMA" ? "de-DE" : "en-US";
}

export function formatCurrency(
  amount: number | string,
  currency: Currency = "EUR",
  style: NumberFormatStyle = "COMMA_DOT"
): string {
  const num = typeof amount === "string" ? parseFloat(amount) : amount;
  return new Intl.NumberFormat(localeForStyle(style), {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
  }).format(num);
}

export function formatDecimal(
  amount: number | string,
  fractionDigits = 2,
  style: NumberFormatStyle = "COMMA_DOT"
): string {
  const num = typeof amount === "string" ? parseFloat(amount) : amount;
  return new Intl.NumberFormat(localeForStyle(style), {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(num);
}

export function formatDate(date: Date | string | null | undefined): string {
  if (!date) return "-";
  return new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
  }).format(new Date(date));
}

export function formatDateTime(date: Date | string | null | undefined): string {
  if (!date) return "-";
  return new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(date));
}

export function formatMinutes(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m}m`;
  if (m === 0) return `${h}h`;
  return `${h}h ${m}m`;
}

export function minutesToHours(minutes: number): number {
  return Math.round((minutes / 60) * 100) / 100;
}

export function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^\w\s-]/g, "")
    .replace(/[\s_-]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

export function buildInvoicePublicUrl(token: string): string {
  return absoluteAppUrl(`/invoice/${token}`);
}

export function buildCompanyAddress(fields: {
  companyAddress?: string | null;
  companyCity?: string | null;
  companyState?: string | null;
  companyCountry?: string | null;
  companyPostalCode?: string | null;
}): string | undefined {
  const locality = [
    fields.companyCity,
    fields.companyState,
    fields.companyPostalCode,
    fields.companyCountry,
  ]
    .map((v) => (typeof v === "string" ? v.trim() : ""))
    .filter(Boolean)
    .join(", ");
  const lines = [fields.companyAddress?.trim() || undefined, locality || undefined].filter(
    (v): v is string => !!v
  );
  return lines.length ? lines.join("\n") : undefined;
}

export function formatBillingAddress(addr: unknown): string | undefined {
  if (!addr) return undefined;
  if (typeof addr === "string") return addr || undefined;
  const a = addr as Record<string, string | undefined>;
  const locality = [a.city, a.state, a.postalCode, a.country]
    .map((v) => (typeof v === "string" ? v.trim() : ""))
    .filter(Boolean)
    .join(", ");
  const lines = [a.line1, a.line2, locality].filter(Boolean);
  return lines.length ? lines.join("\n") : undefined;
}
