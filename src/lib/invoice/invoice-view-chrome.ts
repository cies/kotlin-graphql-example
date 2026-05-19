import { CORPORATE_DEFAULT_ACCENT, resolveCorporateChrome } from "@/lib/pdf/invoice-templates/corporate-tones";
import type { CorporateChrome } from "@/lib/pdf/invoice-templates/corporate-tones";

type OrgInvoiceColorSettings = {
  invoiceAccentColor?: string | null;
  emailAccentColor?: string | null;
} | null;

export type InvoiceViewChrome = {
  accent: string;
  chrome: CorporateChrome;
};

/** Matches customer invoice email accent (`sendInvoiceEmail`): email first, then invoice PDF color. */
export function resolveInvoiceViewChrome(settings: OrgInvoiceColorSettings): InvoiceViewChrome {
  const accent =
    settings?.emailAccentColor?.trim() ||
    settings?.invoiceAccentColor?.trim() ||
    CORPORATE_DEFAULT_ACCENT;
  return { accent, chrome: resolveCorporateChrome(accent) };
}
