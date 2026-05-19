"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { Currency, Payment, PaymentMethod, Prisma, type InvoiceStatus } from "@prisma/client";
import type Stripe from "stripe";
import { stripeClient } from "@/lib/stripe/client";
import { assertRateLimit } from "@/lib/rate-limit/redis";
import { sendMail } from "@/lib/email/mailer";
import { composeEmail } from "@/lib/email/compose";
import {
  buildReceiptPdfDataFromPayment,
  generateReceiptPdf,
  type ReceiptPdfData,
} from "@/lib/pdf/receipt-pdf";
import { receiptDetailsHtml } from "@/lib/receipt/receipt-details-html";
import { generateInvoicePdf, type InvoicePdfData } from "@/lib/pdf/invoice-pdf";
import { logAudit } from "@/lib/audit/log";
import { getNextReceiptNumber } from "@/lib/actions/invoices";
import { invoicePdfCustomerBlock } from "@/lib/invoices/invoice-pdf-customer-block";
import {
  MAX_DOC_NUMBER_ATTEMPTS,
  isPaymentOrgReceiptNumberUniqueConflict,
  isPaymentOrgStripeChargeUniqueConflict,
} from "@/lib/invoices/prisma-doc-number-unique";
import {
  buildCompanyAddress,
  formatCurrency,
  normalizeNumberFormatStyle,
} from "@/lib/utils/format";
import { isAbsoluteHttpUrl } from "@/lib/env/public-app-url";
import { resolvePublicAppOrigin } from "@/lib/env/resolve-public-app-url.server";
import { CORPORATE_DEFAULT_ACCENT } from "@/lib/pdf/invoice-templates/corporate-tones";
import {
  DISPUTE_CLEARED_MERCHANT_OK,
  DISPUTE_LOST_CUSTOMER_WON,
  paymentIndicatesInvoiceChargeback,
} from "@/lib/payments/stripe-dispute-helpers";
import {
  notifyStaffClientPaid,
  notifyStaffPaymentAlert,
} from "@/lib/notifications/staff-notify.server";
import { resolveInvoiceNotificationEmails } from "@/lib/customers/notification-email-resolve";

async function getOrgId(orgSlug: string): Promise<string | null> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") return null;

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) return null;

  const member = await prisma.organizationMember.findUnique({
    where: { organizationId_userId: { organizationId: org.id, userId: session.user.id } },
  });

  return member ? org.id : null;
}

const MONEY_EPS = 0.001;

function paymentNetApplied(p: { amount: unknown; refundedAmount?: unknown | null }): number {
  const amt = parseFloat(String(p.amount));
  const ref = parseFloat(String(p.refundedAmount ?? 0));
  return Math.max(0, amt - ref);
}

/**
 * Recompute invoice amountPaid / paidAt / status from payment rows (+ disputes).
 * Open disputes keep the invoice PAID (funds still credited) until status is lost.
 * Call inside the same prisma transaction that mutates payments.
 */
export async function reconcileInvoiceInTx(
  tx: Prisma.TransactionClient,
  orgId: string,
  invoiceId: string
): Promise<void> {
  const invoice = await tx.invoice.findUnique({
    where: { id: invoiceId, organizationId: orgId },
    include: { payments: true },
  });
  if (!invoice || invoice.status === "VOID") return;

  for (const p of invoice.payments) {
    const st = (p.disputeStatus ?? "").trim();
    if (p.stripeDisputeId && st && DISPUTE_CLEARED_MERCHANT_OK.has(st)) {
      await tx.payment.update({
        where: { id: p.id },
        data: { stripeDisputeId: null, disputeStatus: null },
      });
    }
  }

  const refreshed = await tx.invoice.findUnique({
    where: { id: invoiceId, organizationId: orgId },
    include: { payments: true },
  });
  if (!refreshed || refreshed.status === "VOID") return;

  const { payments } = refreshed;
  const total = parseFloat(refreshed.total.toString());

  let amountPaid = 0;
  for (const p of payments) {
    amountPaid += paymentNetApplied(p);
  }

  const hasChargeback = payments.some((p) => paymentIndicatesInvoiceChargeback(p));

  let paidAt: Date | null = refreshed.paidAt;
  if (amountPaid >= total - MONEY_EPS) {
    paidAt = paidAt ?? new Date();
  } else {
    paidAt = null;
  }

  let status: InvoiceStatus;

  if (hasChargeback) {
    status = "CHARGEBACK";
  } else if (payments.length > 0 && amountPaid <= MONEY_EPS) {
    status = "REFUNDED";
  } else if (amountPaid >= total - MONEY_EPS) {
    status = "PAID";
  } else if (amountPaid > MONEY_EPS) {
    status = "PARTIAL";
  } else if (refreshed.status === "DRAFT") {
    status = "DRAFT";
  } else {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const due = refreshed.dueDate ? new Date(refreshed.dueDate) : null;
    if (due) due.setHours(0, 0, 0, 0);
    status = due && due < today ? "OVERDUE" : "SENT";
  }

  await tx.invoice.update({
    where: { id: invoiceId },
    data: { amountPaid, paidAt, status },
  });
}

/** After payment updates committed outside nested tx helper (webhooks). */
export async function reconcileInvoiceSettlement(orgId: string, invoiceId: string): Promise<void> {
  await prisma.$transaction(async (tx) => reconcileInvoiceInTx(tx, orgId, invoiceId));
}

const manualPaymentSchema = z.object({
  invoiceId: z.string().min(1),
  amount: z.string().min(1),
  currency: z.nativeEnum(Currency),
  method: z.nativeEnum(PaymentMethod).default("MANUAL"),
  paidAt: z.string().optional(),
  notes: z.string().optional(),
});

export type ManualPaymentInput = z.infer<typeof manualPaymentSchema>;

export async function recordManualPayment(orgSlug: string, input: ManualPaymentInput) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = manualPaymentSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const invoice = await prisma.invoice.findUnique({
    where: { id: parsed.data.invoiceId, organizationId: orgId },
    include: {
      customer: { include: { notificationEmails: true } },
      organization: { include: { settings: true } },
    },
  });

  if (!invoice) return { error: "Invoice not found" };
  if (invoice.status === "VOID") return { error: "Cannot record payment on a void invoice" };
  if (invoice.status === "REFUNDED" || invoice.status === "CHARGEBACK") {
    return { error: "Cannot record payment on this invoice" };
  }

  const amount = parseFloat(parsed.data.amount);
  const total = parseFloat(invoice.total.toString());
  const alreadyPaid = parseFloat(invoice.amountPaid.toString());
  const remaining = total - alreadyPaid;

  if (amount <= 0) return { error: "Amount must be positive" };
  if (amount > remaining + MONEY_EPS) {
    return { error: `Payment of ${amount.toFixed(2)} exceeds outstanding balance of ${remaining.toFixed(2)}` };
  }

  let createdPack:
    | { payment: Payment; invoiceStatus: InvoiceStatus }
    | undefined;
  let lastReceiptAllocErr: unknown;
  for (let attempt = 0; attempt < MAX_DOC_NUMBER_ATTEMPTS; attempt++) {
    const receiptNumber = await getNextReceiptNumber(orgId);
    try {
      createdPack = await prisma.$transaction(async (tx) => {
        const payment = await tx.payment.create({
          data: {
            organizationId: orgId,
            invoiceId: invoice.id,
            method: parsed.data.method,
            amount,
            currency: parsed.data.currency,
            paidAt: parsed.data.paidAt ? new Date(parsed.data.paidAt) : new Date(),
            notes: parsed.data.notes,
            receiptNumber,
          },
        });

        await reconcileInvoiceInTx(tx, orgId, invoice.id);
        const inv = await tx.invoice.findUnique({
          where: { id: invoice.id },
          select: { status: true },
        });
        return { payment, invoiceStatus: inv!.status };
      });
      lastReceiptAllocErr = undefined;
      break;
    } catch (e) {
      if (isPaymentOrgReceiptNumberUniqueConflict(e)) {
        lastReceiptAllocErr = e;
        continue;
      }
      throw e;
    }
  }

  if (!createdPack) {
    throw lastReceiptAllocErr instanceof Error
      ? lastReceiptAllocErr
      : new Error("Could not allocate a unique receipt number. Please try again.");
  }

  const { payment: created, invoiceStatus: finalStatus } = createdPack;

  await logAudit({
    organizationId: orgId,
    action: "PAY",
    entityType: "PAYMENT",
    entityId: created.id,
    metadata: {
      invoiceId: invoice.id,
      invoiceNumber: invoice.number,
      amount: amount.toFixed(2),
      currency: parsed.data.currency,
      method: parsed.data.method,
      newStatus: finalStatus,
    },
  });

  const numberFormatStyle = normalizeNumberFormatStyle(
    (invoice.organization.settings as unknown as { numberFormatStyle?: string | null } | null)
      ?.numberFormatStyle
  );
  const customerDisplayName =
    invoice.customer.companyName?.trim() ||
    [invoice.customer.firstName, invoice.customer.lastName].filter(Boolean).join(" ").trim() ||
    invoice.customer.email?.trim() ||
    "Customer";
  const methodLabel =
    { BANK_TRANSFER: "Bank Transfer", MANUAL: "Manual", CASH: "Cash", STRIPE: "Card (Stripe)" }[
      parsed.data.method
    ] ?? parsed.data.method;

  await notifyStaffClientPaid({
    organizationId: orgId,
    invoiceId: invoice.id,
    invoiceNumber: invoice.number,
    customerName: customerDisplayName,
    amountFormatted: formatCurrency(amount.toFixed(2), parsed.data.currency, numberFormatStyle),
    methodLabel,
    invoiceTotalFormatted: formatCurrency(invoice.total.toString(), invoice.currency, numberFormatStyle),
    newStatus: finalStatus,
  });

  if (finalStatus === "PAID" && resolveInvoiceNotificationEmails(invoice.customer).length > 0) {
    const emailed = await deliverReceiptEmail(orgId, invoice.id, {
      paymentId: created.id,
      method: parsed.data.method,
      amount: parsed.data.amount,
      currency: parsed.data.currency,
      paidAt: parsed.data.paidAt ? new Date(parsed.data.paidAt) : new Date(),
      notes: parsed.data.notes,
      receiptNumber: created.receiptNumber ?? undefined,
    });
    if (emailed.error) console.error("[Payments] Receipt email:", emailed.error);
  }

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/${invoice.id}`);
  revalidatePath(`/${orgSlug}/payments`);
  return { success: true };
}

type ReceiptEmailPaymentSlice = {
  paymentId?: string;
  method: PaymentMethod;
  amount: string;
  currency: Currency;
  paidAt: Date;
  stripeChargeId?: string;
  notes?: string;
  receiptNumber?: string;
};

async function deliverReceiptEmail(
  orgId: string,
  invoiceId: string,
  paymentData: ReceiptEmailPaymentSlice
): Promise<{ error?: string }> {
  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: orgId },
    include: {
      customer: { include: { notificationEmails: true } },
      lines: { orderBy: { sortOrder: "asc" } },
      organization: { include: { settings: true } },
    },
  });

  if (!invoice) return { error: "Invoice not found" };
  const toList = resolveInvoiceNotificationEmails(invoice.customer);
  if (toList.length === 0) return {};

  const org = invoice.organization;
  const settings = org.settings;
  const customer = invoice.customer;

  const customerName =
    customer.companyName ??
    [customer.firstName, customer.lastName].filter(Boolean).join(" ") ??
    customer.email ??
    "Customer";

  const pdfData: ReceiptPdfData = buildReceiptPdfDataFromPayment({
    payment: {
      id: paymentData.paymentId,
      receiptNumber: paymentData.receiptNumber,
      amount: paymentData.amount,
      currency: paymentData.currency,
      method: paymentData.method,
      paidAt: paymentData.paidAt,
      stripeChargeId: paymentData.stripeChargeId,
      notes: paymentData.notes,
    },
    invoice: {
      number: invoice.number,
      currency: invoice.currency,
      subtotal: invoice.subtotal,
      vat: invoice.vat,
      total: invoice.total,
      periodFrom: invoice.periodFrom,
      periodTo: invoice.periodTo,
      customer: invoice.customer,
      lines: invoice.lines,
    },
    org: { name: org.name, settings },
  });

  try {
    const pdfBuffer = await generateReceiptPdf(pdfData);
    const paidInvoicePdfData: InvoicePdfData = {
      org: {
        name: settings?.companyName ?? org.name,
        address: buildCompanyAddress(settings ?? {}),
        vat: settings?.companyVat,
        logoUrl: settings?.companyLogoUrl ?? undefined,
        billingEmail: settings?.smtpFrom?.trim() || null,
        phoneContact: settings?.companyPhone?.trim() || null,
      },
      invoice: {
        number: invoice.number,
        status: invoice.status,
        displayStatus: invoice.status === "SENT" ? "UNPAID" : undefined,
        showStatus: (settings as unknown as { showInvoiceStatus?: boolean } | null)?.showInvoiceStatus ?? true,
        currency: invoice.currency,
        issuedAt: invoice.issuedAt,
        dueDate: invoice.dueDate,
        subtotal: invoice.subtotal.toString(),
        vat: invoice.vat.toString(),
        vatRate: parseFloat(invoice.vatRate.toString()),
        total: invoice.total.toString(),
        amountPaid: invoice.amountPaid.toString(),
        discount: invoice.discount.toString(),
        discountType: invoice.discountType,
        discountBeforeTax: invoice.discountBeforeTax,
        notes: invoice.notes,
        termsAndConditions: invoice.termsAndConditions,
        template: invoice.template ?? settings?.defaultInvoiceTemplate ?? "CLASSIC",
        vatIncluded: invoice.vatIncluded,
        periodFrom: invoice.periodFrom ?? undefined,
        periodTo: invoice.periodTo ?? undefined,
      },
      customer: invoicePdfCustomerBlock(customer, customerName),
      lines: invoice.lines.map((line) => ({
        name: line.name,
        description: line.description ?? undefined,
        quantity: line.quantity.toString(),
        qtyType: line.qtyType,
        unitPrice: line.unitPrice.toString(),
        total: line.total.toString(),
      })),
      accentColor: settings?.invoiceAccentColor ?? undefined,
      footerText: settings?.invoiceFooterText ?? undefined,
      numberFormatStyle: normalizeNumberFormatStyle(
        (settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
      ),
      invoicePdfFont: settings?.invoicePdfFont ?? "HELVETICA",
    };
    const paidInvoicePdfBuffer = await generateInvoicePdf(paidInvoicePdfData);

    const methodLabel: Record<string, string> = {
      STRIPE: "Card (Stripe)",
      BANK_TRANSFER: "Bank Transfer",
      MANUAL: "Manual",
      CASH: "Cash",
    };

    const numberFormatStyle = normalizeNumberFormatStyle(
      (settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
    );
    const detailsHtml = receiptDetailsHtml(pdfData);
    const vars = {
      "invoice.number": invoice.number,
      "invoice.currency": invoice.currency,
      "invoice.total": invoice.total.toString(),
      "invoice.totalFormatted": formatCurrency(invoice.total.toString(), invoice.currency, numberFormatStyle),
      "payment.amount": paymentData.amount,
      "payment.amountFormatted": formatCurrency(paymentData.amount, paymentData.currency, numberFormatStyle),
      "payment.method": methodLabel[paymentData.method] ?? paymentData.method,
      "payment.paidAt": paymentData.paidAt.toLocaleDateString("en-GB"),
      "customer.name": customerName,
      "org.name": settings?.companyName ?? org.name,
      "org.accentColor":
        settings?.emailAccentColor?.trim() ||
        settings?.invoiceAccentColor?.trim() ||
        CORPORATE_DEFAULT_ACCENT,
      "receipt.detailsHtml": detailsHtml,
    };

    const { subject, html, attachments } = await composeEmail(orgId, "receipt.paid", vars, [
      {
        filename: `receipt-${invoice.number}.pdf`,
        content: pdfBuffer,
        contentType: "application/pdf",
      },
      {
        filename: `${invoice.number}-paid.pdf`,
        content: paidInvoicePdfBuffer,
        contentType: "application/pdf",
      },
    ]);

    await sendMail({ orgId, to: toList, subject, html, attachments });
    return {};
  } catch (err) {
    console.error("[Payments] Failed to send receipt email:", err);
    return { error: "Failed to send receipt email" };
  }
}

export async function resendPaymentReceipt(orgSlug: string, paymentId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const payment = await prisma.payment.findFirst({
    where: { id: paymentId, organizationId: orgId },
    include: { invoice: { select: { id: true, number: true } } },
  });
  if (!payment) return { error: "Payment not found" };

  const sendResult = await deliverReceiptEmail(orgId, payment.invoiceId, {
    paymentId: payment.id,
    method: payment.method,
    amount: payment.amount.toString(),
    currency: payment.currency,
    paidAt: payment.paidAt,
    stripeChargeId: payment.stripeChargeId ?? undefined,
    notes: payment.notes ?? undefined,
    receiptNumber: payment.receiptNumber ?? undefined,
  });

  if (sendResult.error) {
    return { error: sendResult.error };
  }

  await logAudit({
    organizationId: orgId,
    action: "SEND",
    entityType: "PAYMENT",
    entityId: payment.id,
    metadata: {
      invoiceId: payment.invoiceId,
      invoiceNumber: payment.invoice.number,
      kind: "receipt",
    },
  });

  revalidatePath(`/${orgSlug}/payments`);
  revalidatePath(`/${orgSlug}/invoices/${payment.invoiceId}`);
  revalidatePath(`/${orgSlug}/receipt/${paymentId}`);
  return { success: true as const };
}

/** Propagates to Checkout Session and PaymentIntent for webhook org verification. */
function invoiceStripeMetadata(invoiceId: string, organizationId: string) {
  return { invoiceId, organizationId };
}

type InvoiceCheckoutInvoiceShape = {
  id: string;
  number: string;
  currency: Currency;
  customer: { email: string | null };
};

function buildInvoiceCheckoutSessionCreateParams(input: {
  invoice: InvoiceCheckoutInvoiceShape;
  organizationId: string;
  productDescription: string;
  amountDueCents: number;
  success_url: string;
  cancel_url: string;
}): Stripe.Checkout.SessionCreateParams {
  const meta = invoiceStripeMetadata(input.invoice.id, input.organizationId);
  return {
    mode: "payment",
    line_items: [
      {
        price_data: {
          currency: input.invoice.currency.toLowerCase(),
          product_data: {
            name: `Invoice ${input.invoice.number}`,
            description: input.productDescription,
          },
          unit_amount: input.amountDueCents,
        },
        quantity: 1,
      },
    ],
    metadata: meta,
    payment_intent_data: { metadata: meta },
    success_url: input.success_url,
    cancel_url: input.cancel_url,
    customer_email: input.invoice.customer.email ?? undefined,
  };
}

// ─── Stripe Checkout ──────────────────────────────────────────────────────────

export async function createStripeCheckoutSession(
  orgSlug: string,
  invoiceId: string
): Promise<{ url?: string; error?: string }> {
  const session = await auth();
  if (!session?.user) return { error: "Unauthorized" };

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true, name: true, settings: true },
  });
  if (!org) return { error: "Organization not found" };

  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: org.id },
    include: { lines: true, customer: true },
  });

  if (!invoice) return { error: "Invoice not found" };
  if (["PAID", "VOID", "REFUNDED", "CHARGEBACK"].includes(invoice.status)) {
    return { error: "Invoice is already paid or void" };
  }

  const stripe = await stripeClient(org.id);
  if (!stripe) return { error: "Stripe is not configured for this organization" };

  const amountDue = parseFloat(invoice.total.toString()) - parseFloat(invoice.amountPaid.toString());
  const amountDueCents = Math.round(amountDue * 100);

  if (amountDueCents <= 0) return { error: "No outstanding amount" };

  const baseUrl = await resolvePublicAppOrigin();
  if (!baseUrl) {
    return { error: "Application URL is not configured (set NEXT_PUBLIC_APP_URL, APP_URL, or AUTH_URL)." };
  }

  const successUrl = `${baseUrl}/${orgSlug}/invoices/${invoiceId}?payment=success`;
  const cancelUrl = `${baseUrl}/${orgSlug}/invoices/${invoiceId}?payment=cancelled`;
  if (!isAbsoluteHttpUrl(successUrl) || !isAbsoluteHttpUrl(cancelUrl)) {
    return { error: "Invalid application URL; check NEXT_PUBLIC_APP_URL / APP_URL." };
  }

  try {
    const checkoutSession = await stripe.checkout.sessions.create(
      buildInvoiceCheckoutSessionCreateParams({
        invoice: {
          id: invoice.id,
          number: invoice.number,
          currency: invoice.currency,
          customer: { email: invoice.customer.email },
        },
        organizationId: org.id,
        productDescription: `Payment for invoice ${invoice.number} from ${org.settings?.companyName ?? org.name}`,
        amountDueCents,
        success_url: successUrl,
        cancel_url: cancelUrl,
      })
    );

    return { url: checkoutSession.url ?? undefined };
  } catch (err) {
    console.error("[Stripe] Checkout session creation failed:", err);
    return { error: "Failed to create Stripe Checkout session" };
  }
}

// ─── Portal Stripe Checkout ───────────────────────────────────────────────────

export async function createPortalStripeCheckout(
  orgSlug: string,
  invoiceId: string
): Promise<{ url?: string; error?: string }> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "CUSTOMER_CONTACT") {
    return { error: "Unauthorized" };
  }

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true, name: true, settings: true },
  });
  if (!org) return { error: "Organization not found" };

  const contact = await prisma.customerContact.findFirst({
    where: { organizationId: org.id, userId: session.user.id, canPayInvoices: true },
    select: { customerId: true },
  });
  if (!contact) return { error: "Unauthorized" };

  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: org.id, customerId: contact.customerId },
    include: { customer: true },
  });

  if (!invoice) return { error: "Invoice not found" };
  if (["PAID", "VOID", "REFUNDED", "CHARGEBACK"].includes(invoice.status)) {
    return { error: "Invoice is already paid or void" };
  }

  const stripe = await stripeClient(org.id);
  if (!stripe) return { error: "Online payments are not configured for this organization" };

  const amountDue = parseFloat(invoice.total.toString()) - parseFloat(invoice.amountPaid.toString());
  const amountDueCents = Math.round(amountDue * 100);

  const baseUrl = await resolvePublicAppOrigin();
  if (!baseUrl) {
    return { error: "Application URL is not configured (set NEXT_PUBLIC_APP_URL, APP_URL, or AUTH_URL)." };
  }

  const successUrl = `${baseUrl}/portal/${orgSlug}/invoices/${invoiceId}?payment=success`;
  const cancelUrl = `${baseUrl}/portal/${orgSlug}/invoices/${invoiceId}?payment=cancelled`;
  if (!isAbsoluteHttpUrl(successUrl) || !isAbsoluteHttpUrl(cancelUrl)) {
    return { error: "Invalid application URL; check NEXT_PUBLIC_APP_URL / APP_URL." };
  }

  try {
    const checkoutSession = await stripe.checkout.sessions.create(
      buildInvoiceCheckoutSessionCreateParams({
        invoice: {
          id: invoice.id,
          number: invoice.number,
          currency: invoice.currency,
          customer: { email: invoice.customer.email },
        },
        organizationId: org.id,
        productDescription: `Invoice ${invoice.number}`,
        amountDueCents,
        success_url: successUrl,
        cancel_url: cancelUrl,
      })
    );

    return { url: checkoutSession.url ?? undefined };
  } catch (err) {
    console.error("[Stripe Portal] Checkout session creation failed:", err);
    return { error: "Failed to create payment session" };
  }
}

// ─── Public (token-based) Stripe Checkout ────────────────────────────────────

export async function createPublicCheckoutSession(
  viewToken: string
): Promise<{ url?: string; error?: string }> {
  /**
   * Throttle Checkout session creation per public invoice link.
   * assertRateLimit fails open when REDIS_URL is unset or Redis errors (lib/rate-limit/redis.ts).
   */
  const rateOk = await assertRateLimit(`public-invoice-checkout:${viewToken}`, 25, 3600);
  if (!rateOk) {
    return { error: "Too many payment attempts. Please try again later." };
  }

  const invoice = await prisma.invoice.findUnique({
    where: { viewToken },
    include: { customer: true, organization: { include: { settings: true } } },
  });

  if (!invoice) return { error: "Invoice not found" };
  if (["PAID", "VOID", "REFUNDED", "CHARGEBACK", "DRAFT"].includes(invoice.status)) {
    return { error: "This invoice cannot be paid online" };
  }
  if (invoice.paymentMethod && invoice.paymentMethod !== "STRIPE") {
    return { error: "Online card payment is not enabled for this invoice" };
  }

  const stripe = await stripeClient(invoice.organizationId);
  if (!stripe) return { error: "Online payments are not configured for this organisation" };

  const amountDue =
    parseFloat(invoice.total.toString()) - parseFloat(invoice.amountPaid.toString());
  const amountDueCents = Math.round(amountDue * 100);
  if (amountDueCents <= 0) return { error: "No outstanding amount" };

  const baseUrl = await resolvePublicAppOrigin();
  if (!baseUrl) {
    return { error: "Application URL is not configured (set NEXT_PUBLIC_APP_URL, APP_URL, or AUTH_URL)." };
  }

  const successUrl = `${baseUrl}/invoice/${viewToken}?payment=success`;
  const cancelUrl = `${baseUrl}/invoice/${viewToken}?payment=cancelled`;
  if (!isAbsoluteHttpUrl(successUrl) || !isAbsoluteHttpUrl(cancelUrl)) {
    return { error: "Invalid application URL; check NEXT_PUBLIC_APP_URL / APP_URL." };
  }

  const orgName =
    invoice.organization.settings?.companyName ?? invoice.organization.name;

  try {
    const checkoutSession = await stripe.checkout.sessions.create(
      buildInvoiceCheckoutSessionCreateParams({
        invoice: {
          id: invoice.id,
          number: invoice.number,
          currency: invoice.currency,
          customer: { email: invoice.customer.email },
        },
        organizationId: invoice.organizationId,
        productDescription: `Payment to ${orgName}`,
        amountDueCents,
        success_url: successUrl,
        cancel_url: cancelUrl,
      })
    );

    return { url: checkoutSession.url ?? undefined };
  } catch (err) {
    console.error("[Stripe] Public checkout session creation failed:", err);
    return { error: "Failed to create payment session" };
  }
}

// ─── Internal: handle Stripe webhook payment ─────────────────────────────────

export async function handleStripePaymentSucceeded(
  orgId: string,
  invoiceId: string,
  chargeId: string,
  amount: number,
  currency: string
) {
  if (!chargeId) {
    console.warn("[Stripe] Skipping payment apply: missing charge id");
    return;
  }

  const amountDecimal = amount / 100;
  if (amountDecimal <= 0) return;

  type ApplyResult = {
    payment: Payment;
    invoiceNumber: string;
    invoiceTotal: string;
    customerName: string;
    invoiceCurrency: Currency;
    newStatus: InvoiceStatus;
    amountApplied: number;
  };

  let applied: ApplyResult | null = null;

  try {
    alloc: for (let attempt = 0; attempt < MAX_DOC_NUMBER_ATTEMPTS; attempt++) {
      const receiptNum = await getNextReceiptNumber(orgId);
      try {
        const result = await prisma.$transaction(async (tx) => {
          const invoice = await tx.invoice.findUnique({
            where: { id: invoiceId, organizationId: orgId },
            include: { customer: { include: { notificationEmails: true } } },
          });
          if (
            !invoice ||
            invoice.status === "VOID" ||
            invoice.status === "REFUNDED" ||
            invoice.status === "CHARGEBACK"
          ) {
            return null;
          }

          const existing = await tx.payment.findUnique({
            where: {
              organizationId_stripeChargeId: { organizationId: orgId, stripeChargeId: chargeId },
            },
          });
          if (existing) return null;

          const total = parseFloat(invoice.total.toString());
          const alreadyPaid = parseFloat(invoice.amountPaid.toString());
          const remaining = total - alreadyPaid;
          if (remaining <= 0) return null;

          const amountApplied = Math.min(amountDecimal, remaining);

          try {
            const payment = await tx.payment.create({
              data: {
                organizationId: orgId,
                invoiceId,
                method: "STRIPE",
                amount: amountApplied,
                currency: (currency.toUpperCase() as Currency) ?? invoice.currency,
                stripeChargeId: chargeId,
                receiptNumber: receiptNum,
                paidAt: new Date(),
              },
            });

            await reconcileInvoiceInTx(tx, orgId, invoiceId);
            const invAfter = await tx.invoice.findUnique({
              where: { id: invoiceId },
              select: { status: true },
            });
            const newStatus = invAfter?.status ?? "PARTIAL";

            const customerName =
              invoice.customer.companyName?.trim() ||
              [invoice.customer.firstName, invoice.customer.lastName].filter(Boolean).join(" ").trim() ||
              invoice.customer.email?.trim() ||
              "Customer";

            return {
              payment,
              invoiceNumber: invoice.number,
              invoiceTotal: invoice.total.toString(),
              customerName,
              invoiceCurrency: invoice.currency,
              newStatus,
              amountApplied,
            };
          } catch (e) {
            if (isPaymentOrgStripeChargeUniqueConflict(e)) return null;
            throw e;
          }
        });
        applied = result;
        break alloc;
      } catch (e) {
        if (isPaymentOrgReceiptNumberUniqueConflict(e)) continue alloc;
        throw e;
      }
    }
  } catch (e) {
    console.error("[Stripe] Payment transaction failed:", e);
    return;
  }

  if (!applied) return;

  const {
    payment,
    invoiceNumber,
    invoiceTotal,
    customerName,
    invoiceCurrency,
    newStatus,
    amountApplied,
  } = applied;

  await logAudit({
    organizationId: orgId,
    action: "PAY",
    entityType: "PAYMENT",
    entityId: payment.id,
    metadata: {
      invoiceId,
      invoiceNumber,
      amount: amountApplied.toFixed(2),
      currency: currency.toUpperCase(),
      method: "STRIPE",
      stripeChargeId: chargeId,
      newStatus,
    },
  });

  const nfsRow = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: { numberFormatStyle: true },
  });
  const stripeNfs = normalizeNumberFormatStyle(nfsRow?.numberFormatStyle);
  await notifyStaffClientPaid({
    organizationId: orgId,
    invoiceId,
    invoiceNumber,
    customerName,
    amountFormatted: formatCurrency(amountApplied.toFixed(2), invoiceCurrency, stripeNfs),
    methodLabel: "Card (Stripe)",
    invoiceTotalFormatted: formatCurrency(invoiceTotal, invoiceCurrency, stripeNfs),
    newStatus,
  });

  if (newStatus === "PAID") {
    const emailed = await deliverReceiptEmail(orgId, invoiceId, {
      paymentId: payment.id,
      method: "STRIPE",
      amount: amountApplied.toFixed(2),
      currency: (currency.toUpperCase() as Currency) ?? invoiceCurrency,
      paidAt: new Date(),
      stripeChargeId: chargeId,
      receiptNumber: payment.receiptNumber ?? undefined,
    });
    if (emailed.error) console.error("[Payments] Receipt email:", emailed.error);
  }
}

const manualRefundSchema = z.object({
  amount: z.string().min(1),
  notes: z.string().optional(),
});

export type ManualRefundInput = z.infer<typeof manualRefundSchema>;

/** Non-Stripe / bank-recorded refund; increases `refundedAmount` on the payment row. */
export async function recordManualPaymentRefund(
  orgSlug: string,
  paymentId: string,
  raw: unknown
): Promise<{ success?: true; error?: string }> {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = manualRefundSchema.safeParse(raw);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const payment = await prisma.payment.findFirst({
    where: { id: paymentId, organizationId: orgId },
  });
  if (!payment) return { error: "Payment not found" };
  if (payment.method === "STRIPE" && payment.stripeChargeId) {
    return { error: "Use Stripe refund for card payments" };
  }

  const amt = parseFloat(payment.amount.toString());
  const refunded = parseFloat(String(payment.refundedAmount ?? 0));
  const refundable = amt - refunded;
  const add = parseFloat(parsed.data.amount);
  if (add <= 0 || add > refundable + MONEY_EPS) {
    return { error: `Refund cannot exceed remaining ${refundable.toFixed(2)}` };
  }

  const noteLine = `[Refund ${new Date().toISOString().slice(0, 10)}] ${add.toFixed(2)}${parsed.data.notes ? ` - ${parsed.data.notes}` : ""}`;
  const newNotes = payment.notes ? `${payment.notes}\n${noteLine}` : noteLine;

  await prisma.$transaction(async (tx) => {
    await tx.payment.update({
      where: { id: payment.id },
      data: {
        refundedAmount: refunded + add,
        notes: newNotes,
      },
    });
    await reconcileInvoiceInTx(tx, orgId, payment.invoiceId);
  });

  await logAudit({
    organizationId: orgId,
    action: "REFUND",
    entityType: "PAYMENT",
    entityId: payment.id,
    metadata: {
      invoiceId: payment.invoiceId,
      amount: add.toFixed(2),
      manual: true,
    },
  });

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/${payment.invoiceId}`);
  revalidatePath(`/${orgSlug}/payments`);
  revalidatePath(`/${orgSlug}/receipt/${payment.id}`);
  return { success: true };
}

export async function refundStripePayment(
  orgSlug: string,
  paymentId: string,
  raw?: { amount?: string }
): Promise<{ success?: true; error?: string }> {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const payment = await prisma.payment.findFirst({
    where: { id: paymentId, organizationId: orgId },
  });
  if (!payment) return { error: "Payment not found" };
  if (payment.method !== "STRIPE" || !payment.stripeChargeId) {
    return { error: "This payment is not refundable via Stripe" };
  }

  const amt = parseFloat(payment.amount.toString());
  const refunded = parseFloat(String(payment.refundedAmount ?? 0));
  const refundable = amt - refunded;
  if (refundable <= MONEY_EPS) return { error: "Payment is already fully refunded" };

  const stripe = await stripeClient(orgId);
  if (!stripe) return { error: "Stripe is not configured for this organization" };

  let amountCents: number | undefined;
  if (raw?.amount?.trim()) {
    const part = parseFloat(raw.amount);
    if (part <= 0 || part > refundable + MONEY_EPS) {
      return { error: "Invalid refund amount" };
    }
    amountCents = Math.round(part * 100);
  }

  try {
    await stripe.refunds.create({
      charge: payment.stripeChargeId,
      ...(amountCents != null ? { amount: amountCents } : {}),
    });
  } catch (err) {
    console.error("[Stripe] Refund failed:", err);
    return { error: "Stripe refund failed" };
  }

  const ch = await stripe.charges.retrieve(payment.stripeChargeId);
  const refMajor = (ch.amount_refunded ?? 0) / 100;

  await prisma.payment.update({
    where: { id: payment.id },
    data: { refundedAmount: refMajor },
  });
  await reconcileInvoiceSettlement(orgId, payment.invoiceId);

  await logAudit({
    organizationId: orgId,
    action: "REFUND",
    entityType: "PAYMENT",
    entityId: payment.id,
    metadata: {
      invoiceId: payment.invoiceId,
      stripeChargeId: payment.stripeChargeId,
      amountRefunded: refMajor.toFixed(2),
    },
  });

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/${payment.invoiceId}`);
  revalidatePath(`/${orgSlug}/payments`);
  revalidatePath(`/${orgSlug}/receipt/${payment.id}`);
  return { success: true };
}

/** Full refund of every Stripe charge on an invoice (best-effort; reports first failures). */
export async function refundAllStripePaymentsOnInvoice(
  orgSlug: string,
  invoiceId: string
): Promise<{ success?: true; error?: string }> {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const invoice = await prisma.invoice.findFirst({
    where: { id: invoiceId, organizationId: orgId },
    select: { id: true },
  });
  if (!invoice) return { error: "Invoice not found" };

  const payments = await prisma.payment.findMany({
    where: {
      invoiceId,
      organizationId: orgId,
      method: "STRIPE",
      stripeChargeId: { not: null },
    },
    orderBy: { paidAt: "asc" },
  });

  const failures: string[] = [];
  for (const p of payments) {
    const net = parseFloat(p.amount.toString()) - parseFloat(String(p.refundedAmount ?? 0));
    if (net <= MONEY_EPS) continue;
    const r = await refundStripePayment(orgSlug, p.id);
    if (r.error) failures.push(r.error);
  }

  if (failures.length) return { error: failures[0] };
  return { success: true };
}

/** Webhook: `charge.refunded` - sync cumulative refunded amount on the Payment row. */
export async function applyStripeChargeRefunded(orgId: string, charge: Stripe.Charge): Promise<void> {
  const payment = await prisma.payment.findUnique({
    where: {
      organizationId_stripeChargeId: { organizationId: orgId, stripeChargeId: charge.id },
    },
  });
  if (!payment) return;

  const prevRef = parseFloat(String(payment.refundedAmount ?? 0));
  const refMajor = (charge.amount_refunded ?? 0) / 100;
  await prisma.payment.update({
    where: { id: payment.id },
    data: { refundedAmount: refMajor },
  });
  await reconcileInvoiceSettlement(orgId, payment.invoiceId);

  if (refMajor > prevRef + MONEY_EPS) {
    const inv = await prisma.invoice.findFirst({
      where: { id: payment.invoiceId, organizationId: orgId },
      select: { number: true },
    });
    if (inv) {
      const nfsRow = await prisma.orgSettings.findUnique({
        where: { organizationId: orgId },
        select: { numberFormatStyle: true },
      });
      const nfs = normalizeNumberFormatStyle(nfsRow?.numberFormatStyle);
      const refFmt = formatCurrency(refMajor.toFixed(2), payment.currency, nfs);
      await notifyStaffPaymentAlert({
        organizationId: orgId,
        invoiceId: payment.invoiceId,
        invoiceNumber: inv.number,
        alertTitle: "Stripe refund recorded",
        alertDetail: `Invoice ${inv.number}: cumulative Stripe refunds on this receipt are now ${refFmt}.`,
        sendEmail: true,
      });
    }
  }
}

/** Webhook: dispute created/updated/closed - link dispute to payment and reconcile. */
export async function applyStripeDispute(
  orgId: string,
  dispute: Stripe.Dispute,
  opts?: { stripeEventType?: string }
): Promise<void> {
  const chargeRef = dispute.charge;
  const chargeId =
    typeof chargeRef === "string"
      ? chargeRef
      : chargeRef && typeof chargeRef === "object" && "id" in chargeRef
        ? (chargeRef as { id: string }).id
        : null;
  if (!chargeId) return;

  const payment = await prisma.payment.findUnique({
    where: {
      organizationId_stripeChargeId: { organizationId: orgId, stripeChargeId: chargeId },
    },
  });
  if (!payment) return;

  await prisma.payment.update({
    where: { id: payment.id },
    data: {
      stripeDisputeId: dispute.id,
      disputeStatus: dispute.status,
    },
  });
  await reconcileInvoiceSettlement(orgId, payment.invoiceId);

  const invoice = await prisma.invoice.findFirst({
    where: { id: payment.invoiceId, organizationId: orgId },
    select: { number: true },
  });
  if (!invoice) return;

  const eventType = opts?.stripeEventType ?? "";
  const st = dispute.status;

  if (eventType === "charge.dispute.created") {
    await notifyStaffPaymentAlert({
      organizationId: orgId,
      invoiceId: payment.invoiceId,
      invoiceNumber: invoice.number,
      alertTitle: "Stripe dispute opened",
      alertDetail: `Invoice ${invoice.number}: card dispute (${st}). The invoice stays paid in the CRM until the dispute is lost.`,
      sendEmail: true,
    });
  } else if (DISPUTE_LOST_CUSTOMER_WON.has(st)) {
    await notifyStaffPaymentAlert({
      organizationId: orgId,
      invoiceId: payment.invoiceId,
      invoiceNumber: invoice.number,
      alertTitle: "Chargeback lost",
      alertDetail: `Invoice ${invoice.number}: Stripe dispute status is ${st}. Review the invoice and payment record.`,
      sendEmail: true,
    });
  } else if (DISPUTE_CLEARED_MERCHANT_OK.has(st)) {
    await notifyStaffPaymentAlert({
      organizationId: orgId,
      invoiceId: payment.invoiceId,
      invoiceNumber: invoice.number,
      alertTitle: "Stripe dispute resolved - won",
      alertDetail: `Invoice ${invoice.number}: the dispute closed in your favor (${st}).`,
      sendEmail: false,
    });
  }
}
