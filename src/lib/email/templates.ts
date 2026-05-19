import { prisma } from "@/lib/db/prisma";

/**
 * Default inner HTML bodies - no wrapper divs.
 * The design wrapper in lib/email/designs/ provides the full layout.
 * Merge tags like {{invoice.number}} are substituted at render time.
 */
const DEFAULT_TEMPLATES: Record<string, { subject: string; bodyMjml: string }> = {
  "invoice.sent": {
    subject: "Invoice {{invoice.number}} from {{org.name}}",
    bodyMjml: `<p style="margin:0 0 14px;font-size:15px;line-height:1.5;color:#1e293b">Dear {{customer.name}},</p>
<p style="margin:0 0 18px;font-size:14px;line-height:1.6;color:#475569">Please find attached invoice <strong>{{invoice.number}}</strong> from <strong>{{org.name}}</strong>.</p>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin:0 0 16px;border-collapse:collapse">
  <tr>
    <td style="padding:12px 14px;background:#f8fafc;border-left:4px solid {{org.accentColor}};border-radius:0 8px 8px 0">
      <p style="margin:0;font-size:11px;font-weight:600;color:#64748b;text-transform:uppercase;letter-spacing:0.05em">Amount due</p>
      <p style="margin:6px 0 0;font-size:18px;font-weight:700;color:#1e293b;line-height:1.2">{{invoice.amountDueFormatted}}</p>
      <p style="margin:8px 0 0;font-size:13px;color:#64748b">Due: <strong style="color:#1e293b">{{invoice.dueDate}}</strong></p>
    </td>
  </tr>
</table>
<p style="margin:0 0 16px;font-size:14px;line-height:1.6;color:#475569">{{invoice.notes}}</p>
<p style="margin:0 0 8px">
  <a href="{{invoice.publicUrl}}" style="display:inline-block;padding:10px 22px;background:{{org.accentColor}};color:#ffffff;text-decoration:none;border-radius:6px;font-weight:600;font-size:14px">{{invoice.ctaLabel}}</a>
</p>
<p style="margin:0 0 16px;font-size:12px;color:#94a3b8">A PDF copy is attached to this email.</p>
{{invoice.lineItemsTableHtml}}
<p style="margin:18px 0 0;font-size:12px;color:#94a3b8;line-height:1.5">For the full statement with totals, VAT, and payment history, open the PDF attachment or use <strong>{{invoice.ctaLabel}}</strong> above.</p>`,
  },
  "invoice.overdue": {
    subject: "Overdue: Invoice {{invoice.number}} from {{org.name}}",
    bodyMjml: `<p>Dear {{customer.name}},</p>
<p>This is a reminder that invoice <strong>{{invoice.number}}</strong> for <strong>{{invoice.totalFormatted}}</strong> was due on <strong>{{invoice.dueDate}}</strong> and remains unpaid.</p>
<p style="color:#dc2626;font-weight:600">Payment is now {{daysOverdue}} day(s) overdue.</p>
<p>Please arrange payment at your earliest convenience. If you have any questions, please contact us.</p>`,
  },
  "receipt.paid": {
    subject: "Your receipt - {{invoice.number}} · {{org.name}}",
    bodyMjml: `<p style="margin:0 0 14px;font-size:15px;line-height:1.5;color:#1e293b">Dear {{customer.name}},</p>
<p style="margin:0 0 18px;font-size:14px;line-height:1.6;color:#475569">Thank you for your payment. <strong>{{org.name}}</strong> has recorded your payment against invoice <strong>{{invoice.number}}</strong>. The summary below matches the PDF receipt attached to this message.</p>
<div style="margin:0 0 20px;padding:14px 18px;background:#f8fafc;border-left:4px solid {{org.accentColor}};border-radius:0 8px 8px 0">
  <p style="margin:0;font-size:13px;font-weight:600;color:#1e293b">Payment confirmation</p>
  <p style="margin:6px 0 0;font-size:14px;color:#64748b">{{payment.amountFormatted}} · {{payment.method}} · {{payment.paidAt}}</p>
</div>
{{receipt.detailsHtml}}
<p style="margin:22px 0 0;font-size:13px;line-height:1.55;color:#64748b">If anything looks off, reply to this email and we will help.</p>
<p style="margin:8px 0 0;font-size:13px;color:#94a3b8">- {{org.name}}</p>`,
  },
  "staff.notify.payment_received": {
    subject: "Payment received - {{invoice.number}} · {{payment.amountFormatted}}",
    bodyMjml: `<p style="margin:0 0 12px;font-size:15px;line-height:1.5;color:#1e293b"><strong>{{customer.name}}</strong> paid <strong>{{payment.amountFormatted}}</strong> on invoice <strong>{{invoice.number}}</strong>.</p>
<div style="margin:0 0 18px;padding:14px 18px;background:#f0fdf4;border-left:4px solid {{org.accentColor}};border-radius:0 8px 8px 0">
  <p style="margin:0;font-size:13px;color:#166534">Method: {{payment.method}}</p>
  <p style="margin:6px 0 0;font-size:13px;color:#166534">Invoice total: {{invoice.totalFormatted}} · Status: {{invoice.status}}</p>
</div>
<p style="margin:0 0 16px;font-size:14px;line-height:1.6;color:#475569">Open the invoice in {{org.name}}:</p>
<p style="margin:0 0 8px">
  <a href="{{invoice.staffUrl}}" style="display:inline-block;padding:10px 22px;background:{{org.accentColor}};color:#ffffff;text-decoration:none;border-radius:6px;font-weight:600;font-size:14px">View invoice</a>
</p>
<p style="margin:16px 0 0;font-size:12px;color:#94a3b8">This message was sent because your organization received a payment. Staff-only - do not forward to customers.</p>`,
  },
  "staff.notify.payment_alert": {
    subject: "{{alert.title}} - {{invoice.number}} · {{org.name}}",
    bodyMjml: `<div style="margin:0 0 16px;padding:14px 18px;background:#fffbeb;border-left:4px solid #d97706;border-radius:0 8px 8px 0">
  <p style="margin:0;font-size:15px;font-weight:700;color:#92400e">{{alert.title}}</p>
  <p style="margin:10px 0 0;font-size:14px;line-height:1.55;color:#78350f">{{alert.detail}}</p>
</div>
<p style="margin:0 0 8px">
  <a href="{{invoice.staffUrl}}" style="display:inline-block;padding:10px 22px;background:{{org.accentColor}};color:#ffffff;text-decoration:none;border-radius:6px;font-weight:600;font-size:14px">Open invoice</a>
</p>
<p style="margin:16px 0 0;font-size:12px;color:#94a3b8">Automated staff notification from {{org.name}}.</p>`,
  },
  "task.weekly_digest": {
    subject: "Project status update - {{project.name}}",
    bodyMjml: `<p>Dear {{customer.name}},</p>
<p>Here is a summary of work logged in the past period for <strong>{{project.name}}</strong>:</p>
<div style="background:#f8fafc;border-left:3px solid #2563eb;padding:12px 16px;border-radius:0 6px 6px 0;margin:16px 0">
  {{digest.content}}
</div>
<p>Please don't hesitate to reach out if you have any questions.</p>`,
  },
  "contract.signed": {
    subject: "Contract signed - {{contract.title}}",
    bodyMjml: `<p>The contract <strong>{{contract.title}}</strong> has been signed by <strong>{{signer.name}}</strong> ({{signer.email}}) on {{signer.signedAt}}.</p>
<p>Please find the signed contract attached for your records.</p>`,
  },
  "reminder.generic": {
    subject: "Reminder: {{reminder.title}}",
    bodyMjml: `<h2 style="margin-top:0;font-size:18px">{{reminder.title}}</h2>
<p>{{reminder.description}}</p>
<p style="color:#64748b;font-size:13px;border-top:1px solid #e2e8f0;padding-top:12px;margin-top:16px">Related to: <strong>{{target.label}}</strong><br/>Due: {{reminder.notifyAt}}</p>`,
  },
};

export async function renderTemplate(
  orgId: string,
  key: string,
  vars: Record<string, string>
): Promise<{ subject: string; html: string }> {
  const custom = await prisma.emailTemplate.findUnique({
    where: { organizationId_key: { organizationId: orgId, key } },
  });

  const tpl = custom ?? DEFAULT_TEMPLATES[key];
  if (!tpl) {
    return {
      subject: `Notification from your CRM (${key})`,
      html: `<p>This is an automated notification.</p>`,
    };
  }

  let subject = tpl.subject;
  let html = tpl.bodyMjml;

  for (const [k, v] of Object.entries(vars)) {
    const pattern = new RegExp(`\\{\\{${k.replace(/\./g, "\\.")}\\}\\}`, "g");
    subject = subject.replace(pattern, v ?? "");
    html = html.replace(pattern, v ?? "");
  }

  // Clear any unreplaced placeholders
  subject = subject.replace(/\{\{[^}]+\}\}/g, "");
  html = html.replace(/\{\{[^}]+\}\}/g, "");

  return { subject, html };
}

export function getDefaultTemplates() {
  return DEFAULT_TEMPLATES;
}
