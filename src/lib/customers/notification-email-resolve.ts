import type { CustomerNotificationPurpose } from "@prisma/client";

export type CustomerContactForDigest = {
  isPrimary: boolean;
  user: { email: string | null };
};

export type CustomerForNotificationResolve = {
  email: string | null;
  notificationEmails?: Array<{ purpose: CustomerNotificationPurpose; email: string }>;
  contacts?: CustomerContactForDigest[];
};

function distinctTrimmedEmails(rows: Array<{ email: string }>): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const r of rows) {
    const t = r.email.trim();
    if (!t) continue;
    const k = t.toLowerCase();
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(t);
  }
  return out;
}

/** Emails stored for a given purpose (already normalized when persisted). */
export function notificationEmailsForPurpose(
  customer: Pick<CustomerForNotificationResolve, "notificationEmails">,
  purpose: CustomerNotificationPurpose
): string[] {
  const rows = customer.notificationEmails?.filter((n) => n.purpose === purpose) ?? [];
  return distinctTrimmedEmails(rows);
}

/** Invoice / receipt / reminder delivery — routing addresses or billing email. */
export function resolveInvoiceNotificationEmails(
  customer: CustomerForNotificationResolve
): string[] {
  const routed = notificationEmailsForPurpose(customer, "INVOICES");
  if (routed.length > 0) return routed;
  const bill = customer.email?.trim();
  return bill ? [bill] : [];
}

/** Project status digest — routing addresses or primary portal contact. */
export function resolveDigestNotificationEmails(
  customer: CustomerForNotificationResolve
): string[] {
  const routed = notificationEmailsForPurpose(customer, "DIGEST");
  if (routed.length > 0) return routed;
  const contacts = customer.contacts ?? [];
  const primary = contacts.find((c) => c.isPrimary);
  const ue = primary?.user.email?.trim();
  return ue ? [ue] : [];
}

/** Contract signature request — routing addresses or primary contact / billing. */
export function resolveContractNotificationEmails(
  customer: CustomerForNotificationResolve
): string[] {
  const routed = notificationEmailsForPurpose(customer, "CONTRACTS");
  if (routed.length > 0) return routed;
  const contacts = customer.contacts ?? [];
  const primary = contacts.find((c) => c.isPrimary);
  const ue = primary?.user.email?.trim();
  if (ue) return [ue];
  const bill = customer.email?.trim();
  return bill ? [bill] : [];
}
