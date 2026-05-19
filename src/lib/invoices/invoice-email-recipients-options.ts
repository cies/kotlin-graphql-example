/** Pure helper for staff invoice send/resend dialogs (billing email + portal contacts). */
import type { CustomerNotificationPurpose } from "@prisma/client";
import { notificationEmailsForPurpose } from "@/lib/customers/notification-email-resolve";

export type InvoiceRecipientOption = {
  /** Stable id for checkbox state (distinct from raw email — rows can merge duplicates). */
  key: string;
  email: string;
  label: string;
  isBilling: boolean;
  isPrimary: boolean;
  /** Customer-configured invoice routing row (INVOICES purpose). */
  isInvoiceRouting?: boolean;
};

export type CustomerForInvoiceRecipients = {
  email: string | null;
  companyName?: string | null;
  contacts: Array<{
    id: string;
    isPrimary: boolean;
    user: { email: string | null; name: string | null };
  }>;
  notificationEmails?: Array<{ purpose: CustomerNotificationPurpose; email: string }>;
};

function billingLabel(customer: Pick<CustomerForInvoiceRecipients, "companyName">): string {
  const cn = customer.companyName?.trim();
  return cn ? `Billing (${cn})` : "Billing email";
}

function sortContactsForRows(
  contacts: CustomerForInvoiceRecipients["contacts"]
): CustomerForInvoiceRecipients["contacts"] {
  return [...contacts].sort((a, b) => {
    if (a.isPrimary !== b.isPrimary) return a.isPrimary ? -1 : 1;
    const an = ((a.user.name ?? a.user.email) ?? "").toLowerCase();
    const bn = ((b.user.name ?? b.user.email) ?? "").toLowerCase();
    return an.localeCompare(bn);
  });
}

type ContactAgg = {
  key: string;
  email: string;
  isPrimary: boolean;
  name: string | null;
};

/**
 * Recipient rows shown in Send invoice / Resend dialogs.
 * Merges contacts (and optionally billing) that share the same email into one selectable row.
 */
export function buildInvoiceEmailRecipientRows(
  customer: CustomerForInvoiceRecipients
): InvoiceRecipientOption[] {
  const rows: InvoiceRecipientOption[] = [];
  const routed = notificationEmailsForPurpose(
    { notificationEmails: customer.notificationEmails },
    "INVOICES"
  );
  const routedLc = new Set(routed.map((e) => e.toLowerCase()));

  for (let i = 0; i < routed.length; i++) {
    const email = routed[i]!;
    rows.push({
      key: `inv-route:${i}`,
      email,
      label: `Invoices · ${email}`,
      isBilling: false,
      isPrimary: false,
      isInvoiceRouting: true,
    });
  }

  const billingRaw = customer.email?.trim();

  /** Lower email → merged contact-derived row before billing is folded in */
  const byLc = new Map<string, ContactAgg>();

  const sortedContacts = sortContactsForRows(customer.contacts);
  for (const c of sortedContacts) {
    const ue = c.user.email?.trim();
    if (!ue) continue;
    const lc = ue.toLowerCase();
    if (routedLc.has(lc)) continue;
    let agg = byLc.get(lc);
    const nm = c.user.name?.trim() ?? null;
    if (!agg) {
      agg = {
        key: `c:${c.id}`,
        email: ue,
        isPrimary: c.isPrimary,
        name: nm,
      };
      byLc.set(lc, agg);
      continue;
    }
    agg.isPrimary ||= c.isPrimary;
    agg.name = agg.name?.trim() || nm;
  }

  const pushMergedBillingWithContact = (lc: string, agg: ContactAgg) => {
    const bl = billingLabel(customer);
    const primarySuffix = agg.isPrimary ? " · primary contact" : "";
    const nm = agg.name?.trim();
    const contactLine = nm
      ? `${nm} (${agg.email})${primarySuffix}`
      : `Contact (${agg.email})${primarySuffix}`;
    rows.push({
      key: "billing",
      email: billingRaw!,
      label: `${bl} · ${contactLine}`,
      isBilling: true,
      isPrimary: agg.isPrimary,
    });
    byLc.delete(lc);
  };

  if (billingRaw) {
    const blc = billingRaw.toLowerCase();
    if (!routedLc.has(blc)) {
      const hit = byLc.get(blc);
      if (hit) {
        pushMergedBillingWithContact(blc, hit);
      } else {
        rows.push({
          key: "billing",
          email: billingRaw,
          label: billingLabel(customer),
          isBilling: true,
          isPrimary: false,
        });
      }
    }
  }

  const rest = [...byLc.entries()].sort((a, b) => {
    const [la, aa] = a;
    const [lb, ba] = b;
    if (aa.isPrimary !== ba.isPrimary) return aa.isPrimary ? -1 : 1;
    return la.localeCompare(lb);
  });

  for (const [, agg] of rest) {
    const primarySuffix = agg.isPrimary ? " · primary contact" : "";
    const nm = agg.name?.trim();
    rows.push({
      key: agg.key,
      email: agg.email,
      label: nm
        ? `${nm} (${agg.email})${primarySuffix}`
        : `Contact (${agg.email})${primarySuffix}`,
      isBilling: false,
      isPrimary: agg.isPrimary,
    });
  }

  return rows;
}

/**
 * Defaults when opening the send dialog — avoids selecting everyone when multiple recipients exist.
 * Prefers billing and/or primary contact rows when present; falls back to the first row when none match.
 */
export function defaultInvoiceRecipientKeys(rows: InvoiceRecipientOption[]): Set<string> {
  if (rows.length === 0) return new Set();
  const routing = rows.filter((r) => r.isInvoiceRouting);
  if (routing.length > 0) return new Set(routing.map((r) => r.key));
  const hits = rows.filter((r) => r.isBilling || r.isPrimary);
  if (hits.length > 0) return new Set(hits.map((r) => r.key));
  return new Set([rows[0]!.key]);
}
