/**
 * Staff in-app notifications + optional email for payment-related events.
 * Failures are logged and must not block payment / webhook processing.
 */
import { prisma } from "@/lib/db/prisma";
import { composeEmail } from "@/lib/email/compose";
import { sendMail } from "@/lib/email/mailer";
import { CORPORATE_DEFAULT_ACCENT } from "@/lib/pdf/invoice-templates/corporate-tones";
import { resolvePublicAppOrigin } from "@/lib/env/resolve-public-app-url.server";
import type { InvoiceStatus } from "@prisma/client";

async function getOrgNotifyContext(orgId: string): Promise<{
  slug: string;
  userIds: string[];
  emails: string[];
  orgName: string;
  accentColor: string;
  baseUrl: string;
} | null> {
  const org = await prisma.organization.findUnique({
    where: { id: orgId },
    select: {
      slug: true,
      name: true,
      settings: {
        select: {
          companyName: true,
          emailAccentColor: true,
          invoiceAccentColor: true,
        },
      },
      members: {
        select: {
          user: { select: { id: true, email: true, userType: true } },
        },
      },
    },
  });
  if (!org) return null;

  const userIds: string[] = [];
  const emailSet = new Set<string>();
  for (const m of org.members) {
    if (m.user.userType !== "STAFF") continue;
    userIds.push(m.user.id);
    const e = m.user.email?.trim();
    if (e) emailSet.add(e);
  }

  const baseUrl = (await resolvePublicAppOrigin()) ?? "";
  return {
    slug: org.slug,
    userIds,
    emails: [...emailSet],
    orgName: org.settings?.companyName?.trim() || org.name,
    accentColor:
      org.settings?.emailAccentColor?.trim() ||
      org.settings?.invoiceAccentColor?.trim() ||
      CORPORATE_DEFAULT_ACCENT,
    baseUrl: baseUrl.replace(/\/$/, ""),
  };
}

function staffInvoiceUrl(baseUrl: string, slug: string, invoiceId: string): string {
  if (!baseUrl) return `/${slug}/invoices/${invoiceId}`;
  return `${baseUrl}/${slug}/invoices/${invoiceId}`;
}

function invoiceStaffPath(slug: string, invoiceId: string): string {
  return `/${slug}/invoices/${invoiceId}`;
}

function statusLabelForNotify(status: InvoiceStatus): string {
  switch (status) {
    case "PAID":
      return "Paid in full";
    case "PARTIAL":
      return "Partially paid";
    default:
      return status;
  }
}

export async function notifyStaffClientPaid(params: {
  organizationId: string;
  invoiceId: string;
  invoiceNumber: string;
  customerName: string;
  amountFormatted: string;
  methodLabel: string;
  invoiceTotalFormatted: string;
  newStatus: InvoiceStatus;
}): Promise<void> {
  try {
    const ctx = await getOrgNotifyContext(params.organizationId);
    if (!ctx || ctx.userIds.length === 0) return;

    const link = invoiceStaffPath(ctx.slug, params.invoiceId);
    const abs = staffInvoiceUrl(ctx.baseUrl, ctx.slug, params.invoiceId);
    const stLabel = statusLabelForNotify(params.newStatus);

    await prisma.notification.createMany({
      data: ctx.userIds.map((userId) => ({
        organizationId: params.organizationId,
        userId,
        title: `Payment received: ${params.invoiceNumber}`,
        body: `${params.customerName} paid ${params.amountFormatted} (${params.methodLabel}). ${stLabel}.`,
        link,
      })),
    });

    if (ctx.emails.length === 0) return;

    const { subject, html } = await composeEmail(
      params.organizationId,
      "staff.notify.payment_received",
      {
        "org.name": ctx.orgName,
        "org.accentColor": ctx.accentColor,
        "invoice.number": params.invoiceNumber,
        "invoice.staffUrl": abs,
        "invoice.status": stLabel,
        "invoice.totalFormatted": params.invoiceTotalFormatted,
        "payment.amountFormatted": params.amountFormatted,
        "payment.method": params.methodLabel,
        "customer.name": params.customerName,
      }
    );

    const sent = await sendMail({
      orgId: params.organizationId,
      to: ctx.emails,
      subject,
      html,
    });
    if (!sent.ok) {
      console.warn("[Staff notify] payment_received email skipped:", sent.error);
    }
  } catch (e) {
    console.error("[Staff notify] payment_received failed:", e);
  }
}

export async function notifyStaffPaymentAlert(params: {
  organizationId: string;
  invoiceId: string;
  invoiceNumber: string;
  alertTitle: string;
  alertDetail: string;
  sendEmail: boolean;
}): Promise<void> {
  try {
    const ctx = await getOrgNotifyContext(params.organizationId);
    if (!ctx || ctx.userIds.length === 0) return;

    const link = invoiceStaffPath(ctx.slug, params.invoiceId);
    const abs = staffInvoiceUrl(ctx.baseUrl, ctx.slug, params.invoiceId);

    await prisma.notification.createMany({
      data: ctx.userIds.map((userId) => ({
        organizationId: params.organizationId,
        userId,
        title: params.alertTitle,
        body: params.alertDetail,
        link,
      })),
    });

    if (!params.sendEmail || ctx.emails.length === 0) return;

    const { subject, html } = await composeEmail(
      params.organizationId,
      "staff.notify.payment_alert",
      {
        "org.name": ctx.orgName,
        "org.accentColor": ctx.accentColor,
        "invoice.number": params.invoiceNumber,
        "invoice.staffUrl": abs,
        "alert.title": params.alertTitle,
        "alert.detail": params.alertDetail,
      }
    );

    const sent = await sendMail({
      orgId: params.organizationId,
      to: ctx.emails,
      subject,
      html,
    });
    if (!sent.ok) {
      console.warn("[Staff notify] payment_alert email skipped:", sent.error);
    }
  } catch (e) {
    console.error("[Staff notify] payment_alert failed:", e);
  }
}
