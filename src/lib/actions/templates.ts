"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { assertRateLimit } from "@/lib/rate-limit/redis";
import { revalidatePath, revalidateTag } from "next/cache";
import { orgSettingsCacheTag, getCachedOrgEmailDesign } from "@/lib/settings/cached-org-settings";
import { z } from "zod";
import { logAudit } from "@/lib/audit/log";
import { sanitizeEmailPreviewHtml } from "@/lib/html/sanitize-email-preview";
import { getDefaultTemplates } from "@/lib/email/templates";
import { sendMail } from "@/lib/email/mailer";
import { composeEmail, composeEmailWithOverrides } from "@/lib/email/compose";
import {
  TEMPLATE_SAMPLE_VARS,
  TEMPLATE_KEYS,
  type TemplateKey,
} from "@/lib/email/template-keys";
import { generateInvoicePdf } from "@/lib/pdf/invoice-pdf";
import type { EmailDesign } from "@prisma/client";

const EMAIL_DESIGNS_LIST = ["MODERN_MINIMAL", "CLASSIC_PROFESSIONAL", "BRANDED_BOLD"] as const;

function isTemplateKey(key: string): key is TemplateKey {
  return (TEMPLATE_KEYS as readonly string[]).includes(key);
}

async function getStaffOrgId(orgSlug: string): Promise<string | null> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") return null;

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) return null;

  const member = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: { organizationId: org.id, userId: session.user.id },
    },
    select: { id: true },
  });
  return member ? org.id : null;
}

const upsertTemplateSchema = z.object({
  key: z.string().min(2),
  subject: z.string().min(1),
  bodyMjml: z.string().min(1),
});

export type UpsertTemplateInput = z.infer<typeof upsertTemplateSchema>;

export async function upsertTemplate(orgSlug: string, input: UpsertTemplateInput) {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = upsertTemplateSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  if (!isTemplateKey(parsed.data.key)) {
    return { error: "Unknown template key" };
  }

  const key: TemplateKey = parsed.data.key;
  const existing = await prisma.emailTemplate.findUnique({
    where: { organizationId_key: { organizationId: orgId, key } },
    select: { id: true },
  });

  await prisma.emailTemplate.upsert({
    where: { organizationId_key: { organizationId: orgId, key } },
    update: { subject: parsed.data.subject, bodyMjml: parsed.data.bodyMjml },
    create: {
      organizationId: orgId,
      key,
      subject: parsed.data.subject,
      bodyMjml: parsed.data.bodyMjml,
    },
  });

  await logAudit({
    organizationId: orgId,
    action: existing ? "UPDATE" : "CREATE",
    entityType: "EMAIL_TEMPLATE",
    entityId: key,
    metadata: { key },
  });

  revalidatePath(`/${orgSlug}/templates`);
  revalidatePath(`/${orgSlug}/templates/${key}`);
  return { success: true };
}

export async function resetTemplate(orgSlug: string, key: string) {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  if (!isTemplateKey(key)) return { error: "Unknown template key" };

  await prisma.emailTemplate.deleteMany({
    where: { organizationId: orgId, key },
  });

  await logAudit({
    organizationId: orgId,
    action: "RESET",
    entityType: "EMAIL_TEMPLATE",
    entityId: key,
    metadata: { key },
  });

  revalidatePath(`/${orgSlug}/templates`);
  revalidatePath(`/${orgSlug}/templates/${key}`);
  return { success: true };
}

export async function sendTestTemplate(
  orgSlug: string,
  key: string,
  toEmail: string
) {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  if (!z.string().email().safeParse(toEmail).success) {
    return { error: "Invalid recipient email address" };
  }

  if (!isTemplateKey(key)) return { error: "Unknown template key" };

  const session = await auth();
  if (!session?.user?.id) return { error: "Unauthorized" };
  const testSendOk = await assertRateLimit(
    `tpl-testsend:${session.user.id}:${orgId}`,
    15,
    3600
  );
  if (!testSendOk) {
    return { error: "Test send limit reached. Try again in an hour." };
  }

  const sampleVars = TEMPLATE_SAMPLE_VARS[key];

  // For invoice.sent, attach a generated dummy PDF so the test is realistic
  let extraAttachments: Array<{ filename: string; content: Buffer; contentType: string }> | undefined;
  if (key === "invoice.sent") {
    try {
      const orgSettings = await prisma.orgSettings.findUnique({
        where: { organizationId: orgId },
        select: { invoicePdfFont: true },
      });
      const invoiceNumber = sampleVars["invoice.number"] ?? "INV-2026-0042";
      const pdfBuf = await generateInvoicePdf({
        org: {
          name: sampleVars["org.name"] ?? "Your Company",
          address: sampleVars["org.address"] ?? null,
          vat: sampleVars["org.vat"] ?? null,
          billingEmail: "billing@example.com",
          phoneContact: "US, New York: 1-718-7667744\nUK, London: 44-20-80997699",
        },
        invoice: {
          number: invoiceNumber,
          status: "SENT",
          displayStatus: "UNPAID",
          currency: "EUR",
          issuedAt: new Date("2026-05-01"),
          dueDate: new Date("2026-05-31"),
          periodFrom: new Date("2026-04-01"),
          periodTo: new Date("2026-04-30"),
          subtotal: "1040.00",
          vatRate: 0.20,
          vat: "208.00",
          total: "1248.00",
          amountPaid: "0",
          notes: sampleVars["invoice.notes"] ?? null,
          template: "CLASSIC",
        },
        customer: {
          name: sampleVars["customer.name"] ?? "Acme Industries",
          email: sampleVars["customer.email"] ?? "billing@acme.example",
          phone: "+1 407-467-4257",
          vat: "RO98765432",
          address: "456 Commerce Blvd\nCluj-Napoca, Romania",
        },
        lines: [
          {
            name: "Homepage Redesign",
            description: "Complete visual overhaul of the homepage",
            quantity: "8.50",
            qtyType: "HOURS",
            unitPrice: "80.00",
            total: "680.00",
          },
          {
            name: "Checkout Flow",
            description: "Cart, payment, and confirmation steps",
            quantity: "4.50",
            qtyType: "HOURS",
            unitPrice: "80.00",
            total: "360.00",
          },
        ],
        invoicePdfFont: orgSettings?.invoicePdfFont ?? "HELVETICA",
      });
      extraAttachments = [
        {
          filename: `${invoiceNumber}.pdf`,
          content: pdfBuf,
          contentType: "application/pdf",
        },
      ];
    } catch {
      // Non-fatal - send without PDF if generation fails
    }
  }

  // Use the org's saved template if one exists, otherwise fall back to the default.
  // This ensures test sends reflect the actual template the customer will receive.
  const { subject, html, attachments } = await composeEmail(orgId, key, sampleVars, extraAttachments);

  const taskNote =
    key === "invoice.sent"
      ? ` PDF attachment uses sample data. The default body uses <code>{{invoice.amountDueFormatted}}</code> and <code>{{invoice.lineItemsTableHtml}}</code> (task/time lines → Task / Hours / Rate / Amount; otherwise Item / Qty / Rate / Amount plus period when set). Add <code>{{invoice.detailsHtml}}</code> for the full Corporate totals block.`
      : "";
  const testBanner = `<div style="border:2px dashed #f59e0b;padding:8px;margin-bottom:12px;background:#fffbeb;color:#92400e;font-family:sans-serif;font-size:13px">
    This is a <strong>test send</strong> of the <code>${key}</code> template, with sample data.${taskNote}
  </div>`;

  const result = await sendMail({
    orgId,
    to: toEmail,
    subject: `[TEST] ${subject}`,
    html: testBanner + html,
    attachments,
  });

  await logAudit({
    organizationId: orgId,
    action: "TEST_SEND",
    entityType: "EMAIL_TEMPLATE",
    entityId: key,
    metadata: { key, to: toEmail, ok: result.ok },
  });

  if (!result.ok) return { error: result.error ?? "Failed to send test email" };
  return { success: true };
}

/**
 * Server-only helper used by the editor page to render a fully-composed preview
 * (with design wrapper + branding). Supports unsaved body overrides.
 */
export async function renderTemplatePreview(
  orgSlug: string,
  key: string,
  subjectOverride?: string,
  bodyOverride?: string
): Promise<{ subject: string; html: string } | { error: string }> {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const session = await auth();
  if (!session?.user?.id) return { error: "Unauthorized" };
  const previewOk = await assertRateLimit(
    `tpl-preview:${session.user.id}:${orgId}`,
    90,
    60
  );
  if (!previewOk) {
    return { error: "Too many preview requests. Please wait a minute." };
  }

  if (!isTemplateKey(key)) return { error: "Unknown template key" };

  const sampleVars = TEMPLATE_SAMPLE_VARS[key];

  if (subjectOverride !== undefined && bodyOverride !== undefined) {
    const { subject, html } = await composeEmailWithOverrides(
      orgId,
      key,
      sampleVars,
      subjectOverride,
      bodyOverride
    );
    return { subject, html: sanitizeEmailPreviewHtml(html) };
  }

  const { subject, html } = await composeEmail(orgId, key, sampleVars);
  return { subject, html: sanitizeEmailPreviewHtml(html) };
}

export async function updateEmailDesignForOrg(
  orgSlug: string,
  emailDesign: EmailDesign,
  emailAccentColor: string
) {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  if (!(EMAIL_DESIGNS_LIST as readonly string[]).includes(emailDesign)) {
    return { error: "Invalid email design" };
  }

  await prisma.orgSettings.upsert({
    where: { organizationId: orgId },
    update: { emailDesign, emailAccentColor: emailAccentColor || null },
    create: { organizationId: orgId, emailDesign, emailAccentColor: emailAccentColor || null },
  });

  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "EMAIL_TEMPLATE",
    entityId: "design",
    metadata: { emailDesign, hasAccentColor: !!emailAccentColor },
  });

  revalidateTag(orgSettingsCacheTag(orgId), "default");
  revalidatePath(`/${orgSlug}/templates`);
  return { success: true };
}

export async function getOrgEmailDesign(
  orgSlug: string
): Promise<{ emailDesign: EmailDesign; emailAccentColor: string | null } | null> {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return null;

  return getCachedOrgEmailDesign(orgId);
}

export async function getTemplateForEditor(orgSlug: string, key: string) {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return null;

  if (!isTemplateKey(key)) return null;

  const defaults = getDefaultTemplates();
  const defaultTpl = defaults[key];

  const custom = await prisma.emailTemplate.findUnique({
    where: { organizationId_key: { organizationId: orgId, key } },
  });

  return {
    key,
    subject: custom?.subject ?? defaultTpl?.subject ?? "",
    bodyMjml: custom?.bodyMjml ?? defaultTpl?.bodyMjml ?? "",
    isCustomised: Boolean(custom),
    defaultSubject: defaultTpl?.subject ?? "",
    defaultBody: defaultTpl?.bodyMjml ?? "",
    sampleVars: TEMPLATE_SAMPLE_VARS[key],
  };
}
