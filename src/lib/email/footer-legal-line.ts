/**
 * Fallback footer line for transactional email wrappers when org fields are missing.
 * Tenants can override via OrgSettings.emailEnvelopeFooterLine or normal company fields.
 */
export const DEFAULT_TRANSACTIONAL_EMAIL_FOOTER_LEGAL_LINE =
  "PUZZLE WEBSITE SOLUTIONS LLC, 15442 Ventura Blvd., Ste 201-039, Los Angeles, California, 91403";

export function computeEmailFooterLegalLine(input: {
  envelopeFooterLine: string | null | undefined;
  companyName: string;
  companyAddress: string | null | undefined;
  companyCity: string | null | undefined;
  companyState: string | null | undefined;
  companyPostalCode: string | null | undefined;
  companyCountry: string | null | undefined;
}): string {
  const custom = input.envelopeFooterLine?.trim();
  if (custom) return custom;

  const trim = (v: string | null | undefined) =>
    typeof v === "string" ? v.trim() : "";

  const parts = [
    trim(input.companyName),
    trim(input.companyAddress),
    trim(input.companyCity),
    trim(input.companyState),
    trim(input.companyPostalCode),
    trim(input.companyCountry),
  ].filter(Boolean);

  if (parts.length > 0) return parts.join(", ");

  return DEFAULT_TRANSACTIONAL_EMAIL_FOOTER_LEGAL_LINE;
}
