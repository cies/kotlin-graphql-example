/**
 * Single source of truth for email-template keys, human-friendly metadata
 * and the sample data used for the editor preview / test-send.
 *
 * Workers, server actions, and the templates editor all import from here
 * to stay in sync.
 */

export const TEMPLATE_KEYS = [
  "invoice.sent",
  "invoice.overdue",
  "receipt.paid",
  "staff.notify.payment_received",
  "staff.notify.payment_alert",
  "task.weekly_digest",
  "contract.signed",
  "reminder.generic",
  "member.invite",
] as const;

export type TemplateKey = (typeof TEMPLATE_KEYS)[number];

export interface TemplateMeta {
  key: TemplateKey;
  label: string;
  description: string;
  /** Tags accepted in the subject and body (rendered as quick-reference). */
  availableTags: string[];
}

export const TEMPLATE_META: Record<TemplateKey, TemplateMeta> = {
  "invoice.sent": {
    key: "invoice.sent",
    label: "Invoice - sent to customer",
    description:
      "Sent when staff send an invoice. Includes invoice PDF attachment and public link. Default layout: amount-due callout, notes, primary button labelled {{invoice.ctaLabel}} (Pay by Stripe when Stripe checkout applies; View invoice and payment options for bank transfer, manual, cash, or when Stripe is not configured). Then {{invoice.lineItemsTableHtml}}: Task / Hours / Rate / Amount when any line bills tasks; otherwise Item / Qty / Rate / Amount with invoiced period when set. Optional {{invoice.periodRange}} / {{invoice.periodFrom}} / {{invoice.periodTo}} for custom layouts. Optional {{invoice.detailsHtml}} adds totals, VAT, and payment history.",
    availableTags: [
      "{{invoice.number}}",
      "{{invoice.currency}}",
      "{{invoice.total}}",
      "{{invoice.totalFormatted}}",
      "{{invoice.amountDue}}",
      "{{invoice.amountDueFormatted}}",
      "{{invoice.subtotal}}",
      "{{invoice.vatAmount}}",
      "{{invoice.issueDate}}",
      "{{invoice.dueDate}}",
      "{{invoice.periodFrom}}",
      "{{invoice.periodTo}}",
      "{{invoice.periodRange}}",
      "{{invoice.notes}}",
      "{{invoice.publicUrl}}",
      "{{invoice.detailsHtml}}",
      "{{invoice.linesHtml}}",
      "{{invoice.lineItemsTableHtml}}",
      "{{invoice.tasksHtml}}",
      "{{invoice.tasksBrief}}",
      "{{customer.name}}",
      "{{customer.email}}",
      "{{org.name}}",
      "{{org.address}}",
      "{{org.vat}}",
      "{{org.accentColor}}",
      "{{invoice.ctaLabel}}",
    ],
  },
  "invoice.overdue": {
    key: "invoice.overdue",
    label: "Invoice - overdue reminder",
    description:
      "Automatically sent by the overdue-invoice worker according to the org's overdueReminderDays setting.",
    availableTags: [
      "{{invoice.number}}",
      "{{invoice.currency}}",
      "{{invoice.total}}",
      "{{invoice.totalFormatted}}",
      "{{invoice.dueDate}}",
      "{{customer.name}}",
      "{{org.name}}",
      "{{daysOverdue}}",
    ],
  },
  "receipt.paid": {
    key: "receipt.paid",
    label: "Receipt - payment received",
    description:
      "Sent when an invoice is fully paid. Includes receipt PDF. Uses {{receipt.detailsHtml}} for the Corporate-style receipt block and {{org.accentColor}} for the email accent strip.",
    availableTags: [
      "{{invoice.number}}",
      "{{invoice.currency}}",
      "{{invoice.total}}",
      "{{payment.amount}}",
      "{{payment.amountFormatted}}",
      "{{payment.method}}",
      "{{payment.paidAt}}",
      "{{customer.name}}",
      "{{org.name}}",
      "{{org.accentColor}}",
      "{{receipt.detailsHtml}}",
    ],
  },
  "staff.notify.payment_received": {
    key: "staff.notify.payment_received",
    label: "Staff - client payment recorded",
    description:
      "Sent to every staff member in the organization (SMTP required) when a customer payment is recorded-Stripe, portal checkout, or manual. Does not attach PDFs.",
    availableTags: [
      "{{invoice.number}}",
      "{{invoice.staffUrl}}",
      "{{invoice.status}}",
      "{{invoice.totalFormatted}}",
      "{{payment.amountFormatted}}",
      "{{payment.method}}",
      "{{customer.name}}",
      "{{org.name}}",
      "{{org.accentColor}}",
    ],
  },
  "staff.notify.payment_alert": {
    key: "staff.notify.payment_alert",
    label: "Staff - payment risk (dispute / refund)",
    description:
      "Sent to staff when a Stripe dispute opens, a chargeback is lost, or a Stripe-driven refund changes the books. Won disputes only create an in-app notification by default.",
    availableTags: [
      "{{alert.title}}",
      "{{alert.detail}}",
      "{{invoice.number}}",
      "{{invoice.staffUrl}}",
      "{{org.name}}",
      "{{org.accentColor}}",
    ],
  },
  "task.weekly_digest": {
    key: "task.weekly_digest",
    label: "Project - weekly status digest",
    description:
      "Sent on the project's status email cycle (weekly/biweekly/monthly). Recipients are the customer's Digest routing addresses when set; otherwise the primary portal contact's email.",
    availableTags: [
      "{{project.name}}",
      "{{customer.name}}",
      "{{digest.content}}",
      "{{org.name}}",
    ],
  },
  "contract.signed": {
    key: "contract.signed",
    label: "Contract - signed",
    description:
      "Sent to both parties after a customer signs a contract via the public sign link.",
    availableTags: [
      "{{contract.title}}",
      "{{signer.name}}",
      "{{signer.email}}",
      "{{signer.signedAt}}",
      "{{org.name}}",
    ],
  },
  "reminder.generic": {
    key: "reminder.generic",
    label: "Reminder - generic",
    description:
      "Used by the reminder worker for ad-hoc reminders against a customer/project/task/invoice.",
    availableTags: [
      "{{reminder.title}}",
      "{{reminder.description}}",
      "{{reminder.notifyAt}}",
      "{{target.label}}",
      "{{org.name}}",
    ],
  },
  "member.invite": {
    key: "member.invite",
    label: "Team - member invitation",
    description:
      "Sent when a staff member is invited to join the organization. Contains the accept-invite link.",
    availableTags: [
      "{{inviter.name}}",
      "{{org.name}}",
      "{{invite.role}}",
      "{{invite.acceptUrl}}",
      "{{invite.expiresAt}}",
    ],
  },
};

/**
 * Sample values used to render previews and test sends. Strings only -
 * they're substituted directly into `{{key}}` placeholders.
 */
export const TEMPLATE_SAMPLE_VARS: Record<TemplateKey, Record<string, string>> = {
  "invoice.sent": {
    "invoice.number": "INV-2026-0042",
    "invoice.currency": "EUR",
    "invoice.total": "1,248.00",
    "invoice.totalFormatted": "€1,248.00",
    "invoice.amountDue": "1248.00",
    "invoice.amountDueFormatted": "€1,248.00",
    "invoice.subtotal": "1,040.00",
    "invoice.vatAmount": "208.00",
    "invoice.issueDate": "01/05/2026",
    "invoice.dueDate": "31/05/2026",
    "invoice.periodFrom": "01/04/2026",
    "invoice.periodTo": "30/04/2026",
    "invoice.periodRange": "01/04/2026 – 30/04/2026",
    "invoice.notes": "Thank you for your business. Please reference the invoice number when making payment.",
    "invoice.publicUrl": "https://crm.example.com/invoice/abc123token",
    "invoice.linesHtml":
      `<tr style="border-bottom:1px solid #f1f5f9"><td style="padding:10px 0;font-size:13px;color:#1e293b"><strong>Enterprise licence - Q2</strong></td><td style="padding:10px 8px;text-align:right;font-size:13px;color:#64748b">1</td><td style="padding:10px 8px;text-align:right;font-size:13px;color:#64748b">EUR 990.00</td><td style="padding:10px 0;text-align:right;font-size:13px;font-weight:600;color:#1e293b">EUR 990.00</td></tr>` +
      `<tr style="border-bottom:1px solid #f1f5f9"><td style="padding:10px 0;font-size:13px;color:#1e293b"><strong>Onboarding package</strong><br/><span style="font-size:11px;color:#64748b">Implementation and training</span></td><td style="padding:10px 8px;text-align:right;font-size:13px;color:#64748b">1</td><td style="padding:10px 8px;text-align:right;font-size:13px;color:#64748b">EUR 258.00</td><td style="padding:10px 0;text-align:right;font-size:13px;font-weight:600;color:#1e293b">EUR 258.00</td></tr>`,
    "invoice.lineItemsTableHtml":
      `<p style="margin:16px 0 12px;font-size:13px;line-height:1.5;color:#475569">Invoiced period: <strong style="color:#1e293b">01/04/2026 – 30/04/2026</strong></p>` +
      `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;font-size:13px;margin:0;color:#1e293b">` +
      `<thead><tr style="border-bottom:1px solid #e2e8f0">` +
      `<th style="text-align:left;padding:6px 0;color:#64748b;font-weight:600;font-size:11px">Item</th>` +
      `<th style="text-align:right;padding:6px 0;color:#64748b;font-weight:600;font-size:11px">Qty</th>` +
      `<th style="text-align:right;padding:6px 8px;color:#64748b;font-weight:600;font-size:11px">Rate</th>` +
      `<th style="text-align:right;padding:6px 0;color:#64748b;font-weight:600;font-size:11px">Amount</th>` +
      `</tr></thead><tbody>` +
      `<tr style="border-bottom:1px solid #f1f5f9">` +
      `<td style="padding:8px 0;vertical-align:top"><strong>Enterprise licence - Q2</strong></td>` +
      `<td style="padding:8px 0;text-align:right;color:#64748b;vertical-align:top;white-space:nowrap">1</td>` +
      `<td style="padding:8px 8px;text-align:right;color:#64748b;vertical-align:top;white-space:nowrap">EUR 990.00</td>` +
      `<td style="padding:8px 0;text-align:right;font-weight:600;color:#1e293b;vertical-align:top;white-space:nowrap">EUR 990.00</td>` +
      `</tr>` +
      `<tr style="border-bottom:1px solid #f1f5f9">` +
      `<td style="padding:8px 0;vertical-align:top"><strong>Onboarding package</strong><br/><span style="font-size:11px;color:#64748b">Implementation and training</span></td>` +
      `<td style="padding:8px 0;text-align:right;color:#64748b;vertical-align:top;white-space:nowrap">1</td>` +
      `<td style="padding:8px 8px;text-align:right;color:#64748b;vertical-align:top;white-space:nowrap">EUR 258.00</td>` +
      `<td style="padding:8px 0;text-align:right;font-weight:600;color:#1e293b;vertical-align:top;white-space:nowrap">EUR 258.00</td>` +
      `</tr>` +
      `</tbody></table>`,
    "invoice.tasksHtml":
      `<table width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;font-size:13px;margin:16px 0">` +
      `<thead><tr style="border-bottom:2px solid #e2e8f0">` +
      `<th style="text-align:left;padding:8px 0;color:#64748b;font-weight:600">Task</th>` +
      `<th style="text-align:right;padding:8px 0;color:#64748b;font-weight:600">Hours</th>` +
      `<th style="text-align:right;padding:8px 0;color:#64748b;font-weight:600">Rate</th>` +
      `<th style="text-align:right;padding:8px 0;color:#64748b;font-weight:600">Amount</th>` +
      `</tr></thead><tbody>` +
      `<tr style="border-bottom:1px solid #f1f5f9">` +
      `<td style="padding:10px 0;color:#1e293b;vertical-align:top"><strong>Homepage Redesign</strong><br/><span style="font-size:11px;color:#94a3b8">Website Redesign</span><br/><span style="font-size:11px;color:#64748b">Complete visual overhaul of the homepage</span></td>` +
      `<td style="padding:10px 0;text-align:right;color:#64748b;vertical-align:top">8.50h</td>` +
      `<td style="padding:10px 8px;text-align:right;color:#64748b;vertical-align:top">EUR 80.00</td>` +
      `<td style="padding:10px 0;text-align:right;font-weight:600;color:#1e293b;vertical-align:top">EUR 680.00</td>` +
      `</tr>` +
      `<tr style="background:#f8fafc"><td colspan="2" style="padding:3px 0 3px 16px;font-size:11px;color:#94a3b8">2026-05-02 - Alice Smith - Initial layout implementation</td><td style="padding:3px 0;text-align:right;font-size:11px;color:#94a3b8">5.00h</td><td></td></tr>` +
      `<tr style="background:#f8fafc"><td colspan="2" style="padding:3px 0 3px 16px;font-size:11px;color:#94a3b8">2026-05-04 - Alice Smith - Responsive fixes &amp; mobile polish</td><td style="padding:3px 0;text-align:right;font-size:11px;color:#94a3b8">3.50h</td><td></td></tr>` +
      `<tr style="border-bottom:1px solid #f1f5f9">` +
      `<td style="padding:10px 0;color:#1e293b;vertical-align:top"><strong>Checkout Flow</strong><br/><span style="font-size:11px;color:#94a3b8">Website Redesign</span><br/><span style="font-size:11px;color:#64748b">Cart, payment, and confirmation steps</span></td>` +
      `<td style="padding:10px 0;text-align:right;color:#64748b;vertical-align:top">4.50h</td>` +
      `<td style="padding:10px 8px;text-align:right;color:#64748b;vertical-align:top">EUR 80.00</td>` +
      `<td style="padding:10px 0;text-align:right;font-weight:600;color:#1e293b;vertical-align:top">EUR 360.00</td>` +
      `</tr>` +
      `<tr style="background:#f8fafc"><td colspan="2" style="padding:3px 0 3px 16px;font-size:11px;color:#94a3b8">2026-05-05 - Bob Jones - Cart abandonment &amp; upsell logic</td><td style="padding:3px 0;text-align:right;font-size:11px;color:#94a3b8">2.50h</td><td></td></tr>` +
      `<tr style="background:#f8fafc"><td colspan="2" style="padding:3px 0 3px 16px;font-size:11px;color:#94a3b8">2026-05-06 - Bob Jones - Stripe payment integration</td><td style="padding:3px 0;text-align:right;font-size:11px;color:#94a3b8">2.00h</td><td></td></tr>` +
      `</tbody></table>`,
    "invoice.tasksBrief":
      "• Homepage Redesign (Website Redesign): 8.50h @ EUR 80.00/h = EUR 680.00\n• Checkout Flow (Website Redesign): 4.50h @ EUR 80.00/h = EUR 360.00",
    "customer.name": "Acme Industries",
    "customer.email": "billing@acme.example",
    "org.name": "Your Company",
    "org.address": "123 Main Street, Bucharest, Romania",
    "org.vat": "RO12345678",
    "org.accentColor": "#1d4ed8",
    "invoice.ctaLabel": "Pay by Stripe",
    "invoice.detailsHtml":
      '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;font-family:Helvetica,Arial,sans-serif;color:#111827"><tr><td style="font-size:18px;font-weight:700">Invoice INV-2026-0042</td></tr><tr><td style="padding-top:8px;font-size:12px;color:#64748b">Sample line items, totals, and payment history appear here when sending a real invoice.</td></tr></table>',
  },
  "invoice.overdue": {
    "invoice.number": "INV-2026-0098",
    "invoice.currency": "EUR",
    "invoice.total": "780.00",
    "invoice.totalFormatted": "€780.00",
    "invoice.dueDate": "10/05/2026",
    "customer.name": "Acme Industries",
    "org.name": "Your Company",
    daysOverdue: "7",
  },
  "receipt.paid": {
    "invoice.number": "INV-2026-0123",
    "invoice.currency": "EUR",
    "invoice.total": "1,250.00",
    "payment.amount": "1,250.00",
    "payment.amountFormatted": "€1,250.00",
    "payment.method": "Bank Transfer",
    "payment.paidAt": "06/05/2026",
    "customer.name": "Acme Industries",
    "org.name": "Your Company",
    "org.accentColor": "#1d4ed8",
    "receipt.detailsHtml":
      '<table role="presentation" width="100%"><tr><td style="font-size:17px;font-weight:700">€1,250.00 paid on May 6, 2026</td></tr></table>',
  },
  "staff.notify.payment_received": {
    "invoice.number": "INV-2026-0123",
    "invoice.staffUrl": "https://crm.example.com/acme/invoices/clxyz",
    "invoice.status": "Paid in full",
    "invoice.totalFormatted": "€1,250.00",
    "payment.amountFormatted": "€1,250.00",
    "payment.method": "Card (Stripe)",
    "customer.name": "Acme Industries",
    "org.name": "Your Company",
    "org.accentColor": "#1d4ed8",
  },
  "staff.notify.payment_alert": {
    "alert.title": "Stripe dispute opened",
    "alert.detail":
      "Invoice INV-2026-0123 has an open card dispute (needs_response). The invoice stays paid in the CRM until the dispute is lost.",
    "invoice.number": "INV-2026-0123",
    "invoice.staffUrl": "https://crm.example.com/acme/invoices/clxyz",
    "org.name": "Your Company",
    "org.accentColor": "#1d4ed8",
  },
  "task.weekly_digest": {
    "project.name": "Website Redesign",
    "customer.name": "Acme Industries",
    "digest.content":
      "Total hours logged this week: 18.5h across 6 tasks. Notable items: shipped homepage v2, completed checkout migration.",
    "org.name": "Your Company",
  },
  "contract.signed": {
    "contract.title": "Master Services Agreement 2026",
    "signer.name": "Jane Doe",
    "signer.email": "jane@acme.example",
    "signer.signedAt": "06/05/2026 09:42",
    "org.name": "Your Company",
  },
  "reminder.generic": {
    "reminder.title": "Quarterly review meeting",
    "reminder.description": "Schedule strategy session with the customer.",
    "reminder.notifyAt": "12/05/2026 10:00",
    "target.label": "Acme Industries",
    "org.name": "Your Company",
  },
  "member.invite": {
    "inviter.name": "Alice Smith",
    "org.name": "Your Company",
    "invite.role": "STAFF",
    "invite.acceptUrl": "https://crm.example.com/auth/invite/sample-token",
    "invite.expiresAt": "13/05/2026",
  },
};
