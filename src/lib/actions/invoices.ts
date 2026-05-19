"use server";

import fs from "fs/promises";
import { randomInt } from "node:crypto";
import bcrypt from "bcryptjs";
import path from "path";
import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { Currency, InvoiceStatus, PaymentMethod, InvoiceTemplate, Prisma, type OrgSettings } from "@prisma/client";
import { sendMail } from "@/lib/email/mailer";
import { composeEmail } from "@/lib/email/compose";
import { generateInvoicePdf, type InvoicePdfData } from "@/lib/pdf/invoice-pdf";
import { CORPORATE_DEFAULT_ACCENT } from "@/lib/pdf/invoice-templates/corporate-tones";
import { invoiceDetailsHtml } from "@/lib/invoice/invoice-details-html";
import { assertRateLimit } from "@/lib/rate-limit/redis";
import { logAudit } from "@/lib/audit/log";
import { sanitizeEmailPreviewHtml } from "@/lib/html/sanitize-email-preview";
import { resolveInvoicePublicUrl } from "@/lib/invoices/resolve-invoice-public-url.server";
import {
  buildCompanyAddress,
  formatCurrency,
  normalizeNumberFormatStyle,
  type NumberFormatStyle,
} from "@/lib/utils/format";
import { appendInvoiceEmailOpenPixelAsync } from "@/lib/invoice/email-open-pixel-async.server";
import { nanoid } from "nanoid";
import { getNextInvoiceNumber, getNextReceiptNumber } from "@/lib/invoices/doc-number";
import { invoicePdfCustomerBlock } from "@/lib/invoices/invoice-pdf-customer-block";
import {
  MAX_DOC_NUMBER_ATTEMPTS,
  isInvoiceOrgNumberUniqueConflict,
} from "@/lib/invoices/prisma-doc-number-unique";
import {
  invoiceTimeEntryCalendarIsoDate,
  mergeInvoiceEntriesSameCalendarDayAndUser,
} from "@/lib/filters/time-entry-date-range";
import { computeTotals } from "@/lib/invoices/compute-totals";
import { getFxRate } from "@/lib/invoices/fx-rate";
import {
  recurringRuleCreateSchema,
  recurringRuleTemplateDataSchema,
  type RecurringRuleCreateInput,
} from "@/lib/invoices/recurring-template-schema";
import { invoiceEmailPrimaryCtaLabel } from "@/lib/invoices/invoice-email-cta";
import { computeAutoBilledPeriodFromIssueDate } from "@/lib/invoices/auto-billed-period-from-issue";
import {
  buildInvoiceEmailRecipientRows,
  defaultInvoiceRecipientKeys,
} from "@/lib/invoices/invoice-email-recipients-options";

export { getNextReceiptNumber };

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

// ─── View token helpers ───────────────────────────────────────────────────────

async function mintViewToken(invoiceId: string): Promise<string> {
  const existing = await prisma.invoice.findUnique({
    where: { id: invoiceId },
    select: { viewToken: true },
  });
  if (existing?.viewToken) return existing.viewToken;
  const token = nanoid(32);
  await prisma.invoice.update({
    where: { id: invoiceId },
    data: { viewToken: token, viewTokenCreatedAt: new Date() },
  });
  return token;
}

export async function getInvoicePreviewUrl(
  orgSlug: string,
  invoiceId: string
): Promise<{ url?: string; error?: string }> {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: orgId },
    select: { status: true },
  });
  if (!invoice) return { error: "Invoice not found" };
  if (invoice.status === "DRAFT") {
    return { error: "Draft invoices do not have a public preview URL" };
  }

  const token = await mintViewToken(invoiceId);
  return { url: await resolveInvoicePublicUrl(token) };
}

const lineSchema = z.object({
  name: z.string().min(1),
  description: z.string().optional(),
  quantity: z.string().min(1),
  qtyType: z.enum(["QTY", "HOURS", "QTY_HOURS"]).default("QTY"),
  unitPrice: z.string().min(1),
  sortOrder: z.number().int().default(0),
  taskId: z.string().optional(),
});

const invoiceSchema = z.object({
  customerId: z.string().min(1),
  currency: z.nativeEnum(Currency).default("EUR"),
  dueDate: z.string().optional(),
  issuedAt: z.string().optional(),
  vatRate: z.string().optional(),
  notes: z.string().optional(),
  adminNote: z.string().optional(),
  termsAndConditions: z.string().optional(),
  paymentMethod: z.nativeEnum(PaymentMethod).optional(),
  template: z.nativeEnum(InvoiceTemplate).optional(),
  vatIncluded: z.boolean().optional(),
  periodFrom: z.string().optional(),
  periodTo: z.string().optional(),
  discountType: z.enum(["NONE", "PERCENTAGE", "FIXED"]).default("NONE"),
  discountValue: z.string().optional(),
  discountBeforeTax: z.boolean().default(true),
  // Recurring
  recurring: z.boolean().optional(),
  recurringInterval: z.enum(["WEEKLY", "BIWEEKLY", "MONTHLY"]).optional(),
  recurringStartDate: z.string().optional(),
  recurringEndDate: z.string().optional(),
  recurringBilledPeriodMode: z.enum(["NONE", "AUTO_BY_ISSUE_DATE"]).optional(),
  lines: z.array(lineSchema).min(1),
  /** Overrides org default prefix pattern for this invoice number only (empty = use Settings). */
  invoiceNumberPrefixOverride: z.string().max(200).optional(),
});

export type InvoiceInput = z.infer<typeof invoiceSchema>;

function findDuplicateTaskIds(lines: Array<{ taskId?: string }>): string[] {
  const seen = new Set<string>();
  const duplicates = new Set<string>();
  for (const line of lines) {
    if (!line.taskId) continue;
    if (seen.has(line.taskId)) duplicates.add(line.taskId);
    else seen.add(line.taskId);
  }
  return Array.from(duplicates);
}

function isUnknownMetadataArgError(error: unknown): boolean {
  if (!(error instanceof Error)) return false;
  return error.message.includes("Unknown argument `metadata`");
}

function stripMetadataForNestedCreate<
  T extends {
    metadata?: unknown;
  },
>(lines: T[]): Array<Omit<T, "metadata">> {
  return lines.map(({ metadata: _metadata, ...rest }) => rest);
}

function inferDaysUntilDue(
  dueDate: string | undefined,
  issuedAt: string | undefined,
  fallbackDays: number
): number {
  if (dueDate && issuedAt) {
    const d = Math.round(
      (new Date(dueDate).getTime() - new Date(issuedAt).getTime()) / 86_400_000
    );
    if (!Number.isNaN(d) && d >= 0) return d;
  }
  return fallbackDays;
}

function resolveInvoicePeriodFields(
  data: z.infer<typeof invoiceSchema>,
  issuedAt: Date
): { periodFrom: Date | null; periodTo: Date | null } {
  const pf = data.periodFrom?.trim();
  const pt = data.periodTo?.trim();
  if (pf && pt) {
    return { periodFrom: new Date(pf), periodTo: new Date(pt) };
  }
  const wantsRecurring = data.recurring && data.recurringInterval && data.recurringStartDate;
  const auto =
    !!wantsRecurring &&
    data.recurringInterval === "MONTHLY" &&
    data.recurringBilledPeriodMode === "AUTO_BY_ISSUE_DATE";
  if (auto && !pf && !pt) {
    const p = computeAutoBilledPeriodFromIssueDate(issuedAt);
    return { periodFrom: p.periodFrom, periodTo: p.periodTo };
  }
  return {
    periodFrom: pf ? new Date(pf) : null,
    periodTo: pt ? new Date(pt) : null,
  };
}

function templateBilledPeriodMode(
  interval: "WEEKLY" | "BIWEEKLY" | "MONTHLY" | undefined,
  mode: "NONE" | "AUTO_BY_ISSUE_DATE" | undefined
): "NONE" | "AUTO_BY_ISSUE_DATE" {
  if (interval !== "MONTHLY") return "NONE";
  return mode ?? "NONE";
}

export async function createInvoice(orgSlug: string, input: InvoiceInput) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = invoiceSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };
  const duplicateTaskIds = findDuplicateTaskIds(parsed.data.lines);
  if (duplicateTaskIds.length > 0) {
    return { error: "Each billed task can only be added once per invoice" };
  }

  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: {
      vatRate: true,
      defaultInvoiceTemplate: true,
      defaultVatIncluded: true,
      defaultDueDays: true,
      invoiceNumberFormat: true,
    },
  });

  const prefixOverride = parsed.data.invoiceNumberPrefixOverride?.trim();
  if (
    prefixOverride &&
    /\{RANDOM\}/i.test(prefixOverride) &&
    settings?.invoiceNumberFormat !== "DATE_RANDOM"
  ) {
    return {
      error:
        "A custom invoice prefix cannot contain {RANDOM} unless the organisation number format is 'Date + random' (Settings → Invoice & receipt numbering).",
    };
  }

  const vatRate = parsed.data.vatRate
    ? parseFloat(parsed.data.vatRate)
    : parseFloat(settings?.vatRate?.toString() ?? "0");

  const vatIncluded = parsed.data.vatIncluded ?? settings?.defaultVatIncluded ?? false;
  const resolvedTemplate = parsed.data.template ?? settings?.defaultInvoiceTemplate ?? "CLASSIC";
  const discountType = parsed.data.discountType ?? "NONE";
  const discountValue = parseFloat(parsed.data.discountValue ?? "0") || 0;
  const discountBeforeTax = parsed.data.discountBeforeTax ?? true;
  const defaultDueDays = settings?.defaultDueDays ?? 30;

  const issuedAt = parsed.data.issuedAt ? new Date(parsed.data.issuedAt) : new Date();
  const { periodFrom, periodTo } = resolveInvoicePeriodFields(parsed.data, issuedAt);

  const lineTotals = parsed.data.lines.map((l) => ({
    ...l,
    total: (parseFloat(l.quantity) * parseFloat(l.unitPrice)).toFixed(2),
  }));

  const { subtotal, discount, vat, total } = computeTotals(
    parsed.data.lines, vatRate, vatIncluded, discountType, discountValue, discountBeforeTax
  );
  const fxRate = await getFxRate(orgId, parsed.data.currency);

  // Auto-apply defaultDueDays if dueDate not set
  const dueDateValue = parsed.data.dueDate
    ? new Date(parsed.data.dueDate)
    : settings?.defaultDueDays != null
      ? new Date(Date.now() + settings.defaultDueDays * 86_400_000)
      : null;

  // Snapshot task/time data before billing marks change it
  const taskIdsToSnapshot = lineTotals.filter((l) => l.taskId).map((l) => l.taskId!);
  const taskMetaMap = await snapshotTaskMetadata(taskIdsToSnapshot);

  const lineCreateData = lineTotals.map((l, i) => ({
    name: l.name,
    description: l.description ?? null,
    quantity: l.quantity,
    qtyType: l.qtyType ?? "QTY",
    unitPrice: l.unitPrice,
    total: l.total,
    sortOrder: l.sortOrder ?? i,
    taskId: l.taskId ?? null,
    metadata: prismaJsonForLineMetadata(l.taskId, taskMetaMap),
  }));
  let invoice: Awaited<ReturnType<typeof prisma.invoice.create>> | undefined;
  let allocationError: unknown;
  for (let attempt = 0; attempt < MAX_DOC_NUMBER_ATTEMPTS; attempt++) {
    const number = await getNextInvoiceNumber(orgId, {
      prefixOverride: prefixOverride ?? undefined,
    });
    try {
      try {
        invoice = await prisma.invoice.create({
          data: {
            organizationId: orgId,
            customerId: parsed.data.customerId,
            number,
            currency: parsed.data.currency,
            fxRateToOrgCurrency: fxRate,
            subtotal,
            vat,
            vatRate,
            total,
            discount,
            discountType,
            discountValue,
            discountBeforeTax,
            notes: parsed.data.notes,
            adminNote: parsed.data.adminNote,
            termsAndConditions: parsed.data.termsAndConditions,
            vatIncluded,
            periodFrom,
            periodTo,
            dueDate: dueDateValue,
            issuedAt,
            template: resolvedTemplate,
            paymentMethod: parsed.data.paymentMethod,
            lines: {
              create: lineCreateData,
            },
          },
        });
      } catch (error) {
        if (isInvoiceOrgNumberUniqueConflict(error)) {
          allocationError = error;
          continue;
        }
        if (!isUnknownMetadataArgError(error)) throw error;
        try {
          invoice = await prisma.invoice.create({
            data: {
              organizationId: orgId,
              customerId: parsed.data.customerId,
              number,
              currency: parsed.data.currency,
              fxRateToOrgCurrency: fxRate,
              subtotal,
              vat,
              vatRate,
              total,
              discount,
              discountType,
              discountValue,
              discountBeforeTax,
              notes: parsed.data.notes,
              adminNote: parsed.data.adminNote,
              termsAndConditions: parsed.data.termsAndConditions,
              vatIncluded,
              periodFrom,
              periodTo,
              dueDate: dueDateValue,
              issuedAt,
              template: resolvedTemplate,
              paymentMethod: parsed.data.paymentMethod,
              lines: {
                create: stripMetadataForNestedCreate(lineCreateData),
              },
            },
          });
        } catch (e2) {
          if (isInvoiceOrgNumberUniqueConflict(e2)) {
            allocationError = e2;
            continue;
          }
          throw e2;
        }
      }
      allocationError = undefined;
      break;
    } catch (error) {
      if (isInvoiceOrgNumberUniqueConflict(error)) {
        allocationError = error;
        continue;
      }
      throw error;
    }
  }

  if (!invoice) {
    throw allocationError instanceof Error
      ? allocationError
      : new Error("Could not allocate a unique invoice number. Please try again.");
  }

  // Mark billed time entries for any task-linked lines
  const taskLines = lineTotals.filter((l) => l.taskId);
  if (taskLines.length > 0) {
    const createdLines = await prisma.invoiceLine.findMany({
      where: { invoiceId: invoice.id, taskId: { in: taskLines.map((l) => l.taskId!) } },
      select: { id: true, taskId: true },
    });
    for (const cl of createdLines) {
      if (!cl.taskId) continue;
      await prisma.timeEntry.updateMany({
        where: { taskId: cl.taskId, billed: false },
        data: { billed: true, invoiceLineId: cl.id },
      });
    }
  }

  // Create recurring rule if requested
  if (parsed.data.recurring && parsed.data.recurringInterval && parsed.data.recurringStartDate) {
    const rule = await prisma.recurringInvoiceRule.create({
      data: {
        organizationId: orgId,
        customerId: parsed.data.customerId,
        interval: parsed.data.recurringInterval,
        nextRunAt: new Date(parsed.data.recurringStartDate),
        endAt: parsed.data.recurringEndDate ? new Date(parsed.data.recurringEndDate) : null,
        templateData: {
          currency: parsed.data.currency,
          vatRate: parsed.data.vatRate,
          vatIncluded,
          discountType,
          discountValue: parsed.data.discountValue,
          discountBeforeTax,
          notes: parsed.data.notes,
          termsAndConditions: parsed.data.termsAndConditions,
          paymentMethod: parsed.data.paymentMethod,
          template: resolvedTemplate,
          daysUntilDue: inferDaysUntilDue(
            parsed.data.dueDate,
            parsed.data.issuedAt,
            defaultDueDays
          ),
          billedPeriodMode: templateBilledPeriodMode(
            parsed.data.recurringInterval,
            parsed.data.recurringBilledPeriodMode
          ),
          lines: parsed.data.lines.map((l, i) => ({
            name: l.name,
            description: l.description,
            quantity: l.quantity,
            qtyType: l.qtyType ?? "QTY",
            unitPrice: l.unitPrice,
            sortOrder: l.sortOrder ?? i,
          })),
        },
      },
    });
    await prisma.invoice.update({
      where: { id: invoice.id, organizationId: orgId },
      data: { recurringRuleId: rule.id },
    });
  }

  await logAudit({
    organizationId: orgId,
    action: "CREATE",
    entityType: "INVOICE",
    entityId: invoice.id,
    metadata: {
      number: invoice.number,
      customerId: parsed.data.customerId,
      currency: parsed.data.currency,
      total,
    },
  });

  revalidatePath(`/${orgSlug}/invoices`);
  if (parsed.data.recurring && parsed.data.recurringInterval && parsed.data.recurringStartDate) {
    revalidatePath(`/${orgSlug}/invoices/recurring`);
  }
  return { success: true, invoiceId: invoice.id };
}

export async function updateInvoice(
  orgSlug: string,
  invoiceId: string,
  input: InvoiceInput
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const existing = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: orgId },
    select: { status: true, amountPaid: true, recurringRuleId: true, issuedAt: true },
  });

  if (!existing) return { error: "Invoice not found" };
  if (existing.status !== "DRAFT") return { error: "Only draft invoices can be edited" };

  const parsed = invoiceSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };
  const duplicateTaskIds = findDuplicateTaskIds(parsed.data.lines);
  if (duplicateTaskIds.length > 0) {
    return { error: "Each billed task can only be added once per invoice" };
  }

  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: {
      vatRate: true,
      defaultInvoiceTemplate: true,
      defaultVatIncluded: true,
      defaultDueDays: true,
    },
  });

  const vatRate = parsed.data.vatRate
    ? parseFloat(parsed.data.vatRate)
    : parseFloat(settings?.vatRate?.toString() ?? "0");

  const vatIncluded = parsed.data.vatIncluded ?? settings?.defaultVatIncluded ?? false;
  const resolvedTemplate = parsed.data.template ?? settings?.defaultInvoiceTemplate ?? "CLASSIC";
  const discountType = parsed.data.discountType ?? "NONE";
  const discountValue = parseFloat(parsed.data.discountValue ?? "0") || 0;
  const discountBeforeTax = parsed.data.discountBeforeTax ?? true;
  const defaultDueDays = settings?.defaultDueDays ?? 30;

  const lineTotals = parsed.data.lines.map((l) => ({
    ...l,
    total: (parseFloat(l.quantity) * parseFloat(l.unitPrice)).toFixed(2),
  }));

  const { subtotal, discount, vat, total } = computeTotals(
    parsed.data.lines, vatRate, vatIncluded, discountType, discountValue, discountBeforeTax
  );

  if (parseFloat(total) < parseFloat(existing.amountPaid.toString())) {
    return { error: "New invoice total cannot be less than amount already paid" };
  }

  const fxRate = await getFxRate(orgId, parsed.data.currency);

  const issuedAtForPeriod =
    parsed.data.issuedAt != null && String(parsed.data.issuedAt).trim() !== ""
      ? new Date(parsed.data.issuedAt)
      : existing.issuedAt;
  const { periodFrom, periodTo } = resolveInvoicePeriodFields(parsed.data, issuedAtForPeriod);

  // Snapshot task/time data before any billing marks change
  const taskIdsToSnapshot = lineTotals.filter((l) => l.taskId).map((l) => l.taskId!);
  const taskMetaMap = await snapshotTaskMetadata(taskIdsToSnapshot);

  const newLineData = lineTotals.map((l, i) => ({
    name: l.name,
    description: l.description ?? null,
    quantity: l.quantity,
    qtyType: l.qtyType ?? "QTY",
    unitPrice: l.unitPrice,
    total: l.total,
    sortOrder: l.sortOrder ?? i,
    taskId: l.taskId ?? null,
    metadata: prismaJsonForLineMetadata(l.taskId, taskMetaMap),
  }));

  await prisma.$transaction(async (tx) => {
    // Reset billed status on time entries that were linked to old lines
    const oldLineIds = await tx.invoiceLine.findMany({
      where: { invoiceId },
      select: { id: true },
    });
    if (oldLineIds.length > 0) {
      await tx.timeEntry.updateMany({
        where: { invoiceLineId: { in: oldLineIds.map((l) => l.id) } },
        data: { billed: false, invoiceLineId: null },
      });
    }

    await tx.invoiceLine.deleteMany({ where: { invoiceId } });

    try {
      await tx.invoice.update({
        where: { id: invoiceId, organizationId: orgId },
        data: {
          customerId: parsed.data.customerId,
          currency: parsed.data.currency,
          fxRateToOrgCurrency: fxRate,
          subtotal,
          vat,
          vatRate,
          total,
          discount,
          discountType,
          discountValue,
          discountBeforeTax,
          notes: parsed.data.notes,
          adminNote: parsed.data.adminNote,
          termsAndConditions: parsed.data.termsAndConditions,
          vatIncluded,
          periodFrom,
          periodTo,
          dueDate: parsed.data.dueDate ? new Date(parsed.data.dueDate) : null,
          issuedAt: issuedAtForPeriod,
          template: resolvedTemplate,
          paymentMethod: parsed.data.paymentMethod,
          lines: { create: newLineData },
        },
      });
    } catch (error) {
      if (!isUnknownMetadataArgError(error)) throw error;
      await tx.invoice.update({
        where: { id: invoiceId, organizationId: orgId },
        data: {
          customerId: parsed.data.customerId,
          currency: parsed.data.currency,
          fxRateToOrgCurrency: fxRate,
          subtotal,
          vat,
          vatRate,
          total,
          discount,
          discountType,
          discountValue,
          discountBeforeTax,
          notes: parsed.data.notes,
          adminNote: parsed.data.adminNote,
          termsAndConditions: parsed.data.termsAndConditions,
          vatIncluded,
          periodFrom,
          periodTo,
          dueDate: parsed.data.dueDate ? new Date(parsed.data.dueDate) : null,
          issuedAt: issuedAtForPeriod,
          template: resolvedTemplate,
          paymentMethod: parsed.data.paymentMethod,
          lines: { create: stripMetadataForNestedCreate(newLineData) },
        },
      });
    }

    // Re-mark billed time entries for task-linked lines
    const taskLines = newLineData.filter((l) => l.taskId);
    if (taskLines.length > 0) {
      const createdLines = await tx.invoiceLine.findMany({
        where: { invoiceId, taskId: { in: taskLines.map((l) => l.taskId!) } },
        select: { id: true, taskId: true },
      });
      for (const cl of createdLines) {
        if (!cl.taskId) continue;
        await tx.timeEntry.updateMany({
          where: { taskId: cl.taskId, billed: false },
          data: { billed: true, invoiceLineId: cl.id },
        });
      }
    }

    const recurringTemplateData = {
      currency: parsed.data.currency,
      vatRate: parsed.data.vatRate,
      vatIncluded,
      discountType,
      discountValue: parsed.data.discountValue,
      discountBeforeTax,
      notes: parsed.data.notes,
      termsAndConditions: parsed.data.termsAndConditions,
      paymentMethod: parsed.data.paymentMethod,
      template: resolvedTemplate,
      daysUntilDue: inferDaysUntilDue(
        parsed.data.dueDate,
        parsed.data.issuedAt,
        defaultDueDays
      ),
      billedPeriodMode: templateBilledPeriodMode(
        parsed.data.recurringInterval,
        parsed.data.recurringBilledPeriodMode
      ),
      lines: parsed.data.lines.map((l, i) => ({
        name: l.name,
        description: l.description,
        quantity: l.quantity,
        qtyType: l.qtyType ?? "QTY",
        unitPrice: l.unitPrice,
        sortOrder: l.sortOrder ?? i,
      })),
    };

    const wantsRecurring =
      !!parsed.data.recurring &&
      !!parsed.data.recurringInterval &&
      !!parsed.data.recurringStartDate;
    const nextRunAt = wantsRecurring ? new Date(parsed.data.recurringStartDate!) : null;
    const endAt =
      wantsRecurring && parsed.data.recurringEndDate
        ? new Date(parsed.data.recurringEndDate)
        : null;

    if (wantsRecurring && nextRunAt) {
      if (existing.recurringRuleId) {
        const ruleRow = await tx.recurringInvoiceRule.findFirst({
          where: { id: existing.recurringRuleId, organizationId: orgId },
        });
        const billed = templateBilledPeriodMode(
          parsed.data.recurringInterval,
          parsed.data.recurringBilledPeriodMode
        );
        const mergedTemplate = (() => {
          if (!ruleRow) return { ...recurringTemplateData, billedPeriodMode: billed };
          const prev = recurringRuleTemplateDataSchema.safeParse(ruleRow.templateData);
          if (prev.success) return { ...prev.data, billedPeriodMode: billed };
          return { ...recurringTemplateData, billedPeriodMode: billed };
        })();

        await tx.recurringInvoiceRule.update({
          where: { id: existing.recurringRuleId },
          data: {
            customerId: parsed.data.customerId,
            interval: parsed.data.recurringInterval!,
            nextRunAt,
            endAt,
            active: true,
            templateData: mergedTemplate,
          },
        });
      } else {
        const rule = await tx.recurringInvoiceRule.create({
          data: {
            organizationId: orgId,
            customerId: parsed.data.customerId,
            interval: parsed.data.recurringInterval!,
            nextRunAt,
            endAt,
            templateData: recurringTemplateData,
          },
        });
        await tx.invoice.update({
          where: { id: invoiceId, organizationId: orgId },
          data: { recurringRuleId: rule.id },
        });
      }
    } else if (existing.recurringRuleId) {
      await tx.recurringInvoiceRule.deleteMany({
        where: { id: existing.recurringRuleId, organizationId: orgId },
      });
      await tx.invoice.update({
        where: { id: invoiceId, organizationId: orgId },
        data: { recurringRuleId: null },
      });
    }
  });

  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "INVOICE",
    entityId: invoiceId,
    metadata: { total, lines: parsed.data.lines.length },
  });

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/${invoiceId}`);
  revalidatePath(`/${orgSlug}/invoices/recurring`);
  return { success: true };
}

export async function deleteInvoice(orgSlug: string, invoiceId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const existing = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: orgId },
    select: { status: true, number: true },
  });

  if (!existing) return { error: "Invoice not found" };
  if (!["DRAFT", "VOID"].includes(existing.status)) {
    return { error: "Only draft or void invoices can be deleted" };
  }

  await prisma.invoice.delete({ where: { id: invoiceId, organizationId: orgId } });

  await logAudit({
    organizationId: orgId,
    action: "DELETE",
    entityType: "INVOICE",
    entityId: invoiceId,
    metadata: { number: existing.number },
  });

  revalidatePath(`/${orgSlug}/invoices`);
  return { success: true };
}

export async function requestInvoiceDeletionVerificationCode(
  orgSlug: string,
  invoiceId: string
): Promise<{ success: true } | { error: string }> {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const session = await auth();
  if (!session?.user?.id || !session.user.email) {
    return { error: "Unauthorized" };
  }

  const allowed = await memberIsOwnerOrAdmin(orgId, session.user.id);
  if (!allowed) return { error: "Only organization owners and admins can delete invoices." };

  const inv = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: orgId },
    select: { id: true, number: true },
  });
  if (!inv) return { error: "Invoice not found" };

  const safeNumber = inv.number
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  const code = String(randomInt(100000, 999999));
  const codeHash = await bcrypt.hash(code, 10);

  await prisma.invoiceDeletionVerification.deleteMany({
    where: { invoiceId, userId: session.user.id },
  });

  await prisma.invoiceDeletionVerification.create({
    data: {
      organizationId: orgId,
      invoiceId,
      userId: session.user.id,
      codeHash,
      expiresAt: new Date(Date.now() + 15 * 60 * 1000),
    },
  });

  const result = await sendMail({
    orgId,
    to: session.user.email,
    subject: `Deletion code for invoice ${inv.number}`,
    html: `<p style="font-family:sans-serif;font-size:15px;line-height:1.55;color:#0f172a">Your verification code to permanently delete invoice <strong>${safeNumber}</strong> is:</p>
<p style="font-family:ui-monospace,monospace;font-size:26px;font-weight:700;letter-spacing:0.12em;color:#1e293b">${code}</p>
<p style="font-size:13px;color:#64748b;line-height:1.5">This code expires in 15 minutes and can only be used once. If you did not ask to delete this invoice, contact your team immediately.</p>`,
  });

  if (!result.ok) {
    await prisma.invoiceDeletionVerification.deleteMany({
      where: { invoiceId, userId: session.user.id },
    });
    const hint =
      result.error === "SMTP not configured for this organization."
        ? " Configure SMTP in Settings (same as outbound invoice mail)."
        : "";
    return { error: `${result.error ?? "Could not send verification email."}${hint}` };
  }

  await logAudit({
    organizationId: orgId,
    action: "SEND",
    entityType: "INVOICE",
    entityId: invoiceId,
    metadata: { kind: "deletion_otp_requested", number: inv.number },
  });

  return { success: true };
}

export async function deleteInvoiceWithVerificationCode(
  orgSlug: string,
  invoiceId: string,
  code: string
): Promise<{ success: true } | { error: string }> {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const session = await auth();
  if (!session?.user?.id) return { error: "Unauthorized" };

  const allowed = await memberIsOwnerOrAdmin(orgId, session.user.id);
  if (!allowed) return { error: "Only organization owners and admins can delete invoices." };

  const digits = code.replace(/\D/g, "");
  if (digits.length !== 6) return { error: "Enter the 6-digit code from your email." };

  const row = await prisma.invoiceDeletionVerification.findFirst({
    where: {
      invoiceId,
      userId: session.user.id,
      expiresAt: { gt: new Date() },
    },
    orderBy: { createdAt: "desc" },
  });

  if (!row) return { error: "No active code. Request a new verification email." };

  const match = await bcrypt.compare(digits, row.codeHash);
  if (!match) return { error: "Invalid code." };

  const existing = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: orgId },
    select: { number: true, recurringRuleId: true },
  });
  if (!existing) return { error: "Invoice not found" };

  await prisma.$transaction(async (tx) => {
    await tx.invoiceDeletionVerification.deleteMany({ where: { invoiceId } });

    if (existing.recurringRuleId) {
      await tx.invoice.update({
        where: { id: invoiceId, organizationId: orgId },
        data: { recurringRuleId: null },
      });
      await tx.recurringInvoiceRule.deleteMany({
        where: { id: existing.recurringRuleId, organizationId: orgId },
      });
    }

    const lines = await tx.invoiceLine.findMany({
      where: { invoiceId },
      select: { id: true },
    });
    const lineIds = lines.map((l) => l.id);
    if (lineIds.length > 0) {
      await tx.timeEntry.updateMany({
        where: { invoiceLineId: { in: lineIds } },
        data: { billed: false, invoiceLineId: null },
      });
    }

    await tx.invoice.delete({ where: { id: invoiceId, organizationId: orgId } });
  });

  await logAudit({
    organizationId: orgId,
    action: "DELETE",
    entityType: "INVOICE",
    entityId: invoiceId,
    metadata: { number: existing.number, emailVerifiedDeletion: true },
  });

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/customers`);
  return { success: true };
}

async function memberIsOwnerOrAdmin(orgId: string, userId: string): Promise<boolean> {
  const m = await prisma.organizationMember.findUnique({
    where: { organizationId_userId: { organizationId: orgId, userId } },
    select: { role: true },
  });
  return m?.role === "OWNER" || m?.role === "ADMIN";
}

export async function voidInvoice(orgSlug: string, invoiceId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const payments = await prisma.payment.findMany({
    where: { invoiceId, organizationId: orgId },
    select: { amount: true, refundedAmount: true },
  });
  for (const p of payments) {
    const net =
      parseFloat(p.amount.toString()) -
      parseFloat((p.refundedAmount ?? 0).toString());
    if (net > 0.001) {
      return {
        error:
          "Cannot void an invoice with outstanding payments. Record refunds or Stripe refunds first.",
      };
    }
  }

  await prisma.$transaction(async (tx) => {
    // Reset billed status on all time entries linked to this invoice's lines,
    // so the hours become billable again on a future invoice.
    const lines = await tx.invoiceLine.findMany({
      where: { invoiceId },
      select: { id: true },
    });
    if (lines.length > 0) {
      await tx.timeEntry.updateMany({
        where: { invoiceLineId: { in: lines.map((l) => l.id) } },
        data: { billed: false, invoiceLineId: null },
      });
    }

    await tx.invoice.update({
      where: { id: invoiceId, organizationId: orgId },
      data: { status: "VOID" },
    });
  });

  await logAudit({
    organizationId: orgId,
    action: "VOID",
    entityType: "INVOICE",
    entityId: invoiceId,
  });

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/${invoiceId}`);
  return { success: true };
}

export async function duplicateInvoiceAsDraft(orgSlug: string, sourceInvoiceId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const source = await prisma.invoice.findUnique({
    where: { id: sourceInvoiceId, organizationId: orgId },
    include: { lines: { orderBy: { sortOrder: "asc" } } },
  });
  if (!source) return { error: "Invoice not found" };

  const fxRate = await getFxRate(orgId, source.currency);

  const lineCreateData = source.lines.map((l, i) => ({
    name: l.name,
    description: l.description ?? null,
    quantity: l.quantity,
    qtyType: l.qtyType,
    unitPrice: l.unitPrice,
    total: l.total,
    sortOrder: l.sortOrder ?? i,
    taskId: null as null,
  }));

  let newInv: Awaited<ReturnType<typeof prisma.invoice.create>> | undefined;
  let lastErr: unknown;
  for (let attempt = 0; attempt < MAX_DOC_NUMBER_ATTEMPTS; attempt++) {
    const number = await getNextInvoiceNumber(orgId);
    try {
      newInv = await prisma.invoice.create({
        data: {
          organizationId: orgId,
          customerId: source.customerId,
          number,
          status: "DRAFT",
          currency: source.currency,
          fxRateToOrgCurrency: fxRate,
          subtotal: source.subtotal,
          vat: source.vat,
          vatRate: source.vatRate,
          total: source.total,
          amountPaid: 0,
          dueDate: source.dueDate,
          issuedAt: new Date(),
          paidAt: null,
          notes: source.notes,
          adminNote: source.adminNote,
          viewToken: null,
          viewTokenCreatedAt: null,
          paymentMethod: source.paymentMethod,
          template: source.template,
          vatIncluded: source.vatIncluded,
          periodFrom: source.periodFrom,
          periodTo: source.periodTo,
          discountType: source.discountType,
          discountValue: source.discountValue,
          discountBeforeTax: source.discountBeforeTax,
          discount: source.discount,
          termsAndConditions: source.termsAndConditions,
          lines: { create: lineCreateData },
        },
      });
      lastErr = undefined;
      break;
    } catch (e) {
      if (isInvoiceOrgNumberUniqueConflict(e)) {
        lastErr = e;
        continue;
      }
      throw e;
    }
  }
  if (newInv === undefined) {
    throw lastErr instanceof Error
      ? lastErr
      : new Error("Could not allocate a unique invoice number. Please try again.");
  }

  await logAudit({
    organizationId: orgId,
    action: "CREATE",
    entityType: "INVOICE",
    entityId: newInv.id,
    metadata: { number: newInv.number, sourceInvoiceId, duplicatedFrom: source.number },
  });

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/${newInv.id}`);
  return { success: true, invoiceId: newInv.id };
}

export async function deleteInvoiceAttachment(orgSlug: string, attachmentId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const attachment = await prisma.invoiceAttachment.findUnique({
    where: { id: attachmentId, organizationId: orgId },
    include: { invoice: { select: { id: true, number: true } } },
  });
  if (!attachment) return { error: "Attachment not found" };

  try {
    await fs.unlink(path.join(process.cwd(), attachment.storagePath));
  } catch {
    // ignore
  }

  await prisma.invoiceAttachment.delete({ where: { id: attachmentId } });

  await logAudit({
    organizationId: orgId,
    action: "DELETE",
    entityType: "INVOICE_ATTACHMENT",
    entityId: attachmentId,
    metadata: { invoiceId: attachment.invoiceId, filename: attachment.filename, number: attachment.invoice.number },
  });

  revalidatePath(`/${orgSlug}/invoices/${attachment.invoiceId}`);
  return { success: true };
}

export async function createInvoiceNote(orgSlug: string, invoiceId: string, content: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const inv = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: orgId },
    select: { id: true },
  });
  if (!inv) return { error: "Invoice not found" };

  const session = await auth();
  if (!session?.user?.id) return { error: "Unauthorized" };

  const note = await prisma.invoiceNote.create({
    data: {
      organizationId: orgId,
      invoiceId,
      authorId: session.user.id,
      content: content.trim(),
    },
  });

  await logAudit({
    organizationId: orgId,
    action: "CREATE",
    entityType: "INVOICE_NOTE",
    entityId: note.id,
    metadata: { invoiceId },
  });

  revalidatePath(`/${orgSlug}/invoices/${invoiceId}`);
  return { success: true };
}

export async function deleteInvoiceNote(orgSlug: string, invoiceId: string, noteId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const note = await prisma.invoiceNote.findUnique({
    where: { id: noteId, invoiceId, organizationId: orgId },
  });
  if (!note) return { error: "Note not found" };

  await prisma.invoiceNote.delete({ where: { id: noteId } });

  await logAudit({
    organizationId: orgId,
    action: "DELETE",
    entityType: "INVOICE_NOTE",
    entityId: noteId,
    metadata: { invoiceId },
  });

  revalidatePath(`/${orgSlug}/invoices/${invoiceId}`);
  return { success: true };
}

function formatInvoicePeriodRangeLabel(
  periodFrom: Date | null | undefined,
  periodTo: Date | null | undefined
): string {
  if (!periodFrom && !periodTo) return "";
  const fmt = (d: Date) => new Date(d).toLocaleDateString("en-GB");
  if (periodFrom && periodTo) return `${fmt(periodFrom)} – ${fmt(periodTo)}`;
  if (periodFrom) return `From ${fmt(periodFrom)}`;
  return `Until ${fmt(periodTo!)}`;
}

function formatInvoicePeriodSummaryHtml(
  periodFrom: Date | null | undefined,
  periodTo: Date | null | undefined
): string {
  const label = formatInvoicePeriodRangeLabel(periodFrom, periodTo);
  if (!label) return "";
  return `<p style="margin:16px 0 12px;font-size:13px;line-height:1.5;color:#475569">Invoiced period: <strong style="color:#1e293b">${escHtml(label)}</strong></p>`;
}

async function buildInvoiceSentTemplateVars(params: {
  invoice: {
    number: string;
    currency: Currency;
    total: { toString(): string };
    amountPaid: { toString(): string };
    subtotal: { toString(): string };
    vat: { toString(): string };
    issuedAt: Date;
    dueDate: Date | null;
    periodFrom: Date | null;
    periodTo: Date | null;
    notes: string | null;
    discountValue: { toString(): string } | null | undefined;
    lines: LineSummary[];
    payments: Array<{
      paidAt: Date;
      method: PaymentMethod;
      amount: { toString(): string };
      currency: Currency;
      stripeChargeId: string | null;
      notes: string | null;
    }>;
    customer: { email: string | null };
    /** DB status - DRAFT is treated like SENT for customer-facing CTAs while sending */
    status: InvoiceStatus;
    paymentMethod: PaymentMethod | null;
  };
  org: { name: string };
  settings: OrgSettings | null;
  pdfData: InvoicePdfData;
  publicUrl: string;
  customerName: string;
}): Promise<Record<string, string>> {
  const { invoice, org, settings, pdfData, publicUrl, customerName } = params;
  const numberFormatStyle = normalizeNumberFormatStyle(
    (settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
  );
  const taskLookup = await buildTaskLookup(invoice.lines);
  const paymentSlices = invoice.payments.map((p) => ({
    paidAt: p.paidAt,
    method: p.method,
    amount: p.amount.toString(),
    currency: p.currency,
    stripeChargeId: p.stripeChargeId,
    notes: p.notes,
  }));
  const detailsHtml = invoiceDetailsHtml(pdfData, paymentSlices, {
    discountValue: invoice.discountValue?.toString() ?? null,
  });
  const accent =
    settings?.emailAccentColor?.trim() ||
    settings?.invoiceAccentColor?.trim() ||
    CORPORATE_DEFAULT_ACCENT;

  const totalNum = parseFloat(invoice.total.toString());
  const paidNum = parseFloat(invoice.amountPaid.toString());
  const dueNum = Math.max(0, Number.isFinite(totalNum) ? totalNum - (Number.isFinite(paidNum) ? paidNum : 0) : 0);

  const stripeConfigured = Boolean(settings?.stripeSecretKey?.trim());
  const statusForCustomerEmail: InvoiceStatus = invoice.status === "DRAFT" ? "SENT" : invoice.status;
  const ctaLabel = invoiceEmailPrimaryCtaLabel({
    invoiceStatus: statusForCustomerEmail,
    paymentMethod: invoice.paymentMethod,
    stripeConfigured,
    amountDueNum: dueNum,
  });

  return {
    "invoice.number": invoice.number,
    "invoice.currency": invoice.currency,
    "invoice.total": invoice.total.toString(),
    "invoice.totalFormatted": formatCurrency(invoice.total.toString(), invoice.currency, numberFormatStyle),
    "invoice.amountDue": dueNum.toFixed(2),
    "invoice.amountDueFormatted": formatCurrency(dueNum.toFixed(2), invoice.currency, numberFormatStyle),
    "invoice.subtotal": invoice.subtotal.toString(),
    "invoice.vatAmount": invoice.vat.toString(),
    "invoice.issueDate": new Date(invoice.issuedAt).toLocaleDateString("en-GB"),
    "invoice.dueDate": invoice.dueDate
      ? new Date(invoice.dueDate).toLocaleDateString("en-GB")
      : "-",
    "invoice.notes": invoice.notes ?? "",
    "invoice.publicUrl": publicUrl,
    "invoice.detailsHtml": detailsHtml,
    "invoice.periodFrom": invoice.periodFrom
      ? new Date(invoice.periodFrom).toLocaleDateString("en-GB")
      : "",
    "invoice.periodTo": invoice.periodTo
      ? new Date(invoice.periodTo).toLocaleDateString("en-GB")
      : "",
    "invoice.periodRange": formatInvoicePeriodRangeLabel(invoice.periodFrom, invoice.periodTo),
    "invoice.lineItemsTableHtml": buildInvoiceLineItemsTableHtml(
      invoice.lines,
      invoice.currency,
      numberFormatStyle,
      taskLookup,
      invoice.periodFrom,
      invoice.periodTo
    ),
    "invoice.linesHtml": invoice.lines
      .map(
        (l) =>
          `<tr style="border-bottom:1px solid #f1f5f9">` +
          `<td style="padding:10px 0;font-size:13px;color:#1e293b">` +
          `<strong>${escHtml(l.name)}</strong>` +
          (l.description ? `<br/><span style="font-size:11px;color:#64748b">${escHtml(l.description)}</span>` : "") +
          `</td>` +
          `<td style="padding:10px 8px;text-align:right;font-size:13px;color:#64748b">${escHtml(l.quantity.toString())}</td>` +
          `<td style="padding:10px 8px;text-align:right;font-size:13px;color:#64748b">${escHtml(formatCurrency(l.unitPrice.toString(), invoice.currency, numberFormatStyle))}</td>` +
          `<td style="padding:10px 0;text-align:right;font-size:13px;font-weight:600;color:#1e293b">${escHtml(formatCurrency(l.total.toString(), invoice.currency, numberFormatStyle))}</td>` +
          `</tr>`
      )
      .join(""),
    "invoice.tasksHtml": buildTasksHtml(invoice.lines, invoice.currency, numberFormatStyle, taskLookup),
    "invoice.tasksBrief": buildTasksBrief(invoice.lines, invoice.currency, numberFormatStyle, taskLookup),
    "customer.name": customerName,
    "customer.email": invoice.customer.email ?? "",
    "org.name": settings?.companyName ?? org.name,
    "org.address": settings?.companyAddress ?? "",
    "org.vat": settings?.companyVat ?? "",
    "org.accentColor": accent,
    "invoice.ctaLabel": ctaLabel,
  };
}

const MAX_INVOICE_BCC = 5;

function parseInvoiceBccList(
  options?: { bcc?: string | string[] }
): { ok: true; emails: string[] } | { ok: false; error: string } {
  if (!options?.bcc) return { ok: true, emails: [] };

  const raw = Array.isArray(options.bcc) ? options.bcc : [options.bcc];
  const trimmed = raw.map((s) => s.trim()).filter(Boolean);

  const seen = new Set<string>();
  const emails: string[] = [];
  for (const addr of trimmed) {
    const emailCheck = z.string().email().safeParse(addr);
    if (!emailCheck.success) {
      return { ok: false, error: "Invalid BCC email address." };
    }
    const lower = emailCheck.data.toLowerCase();
    if (seen.has(lower)) continue;
    if (emails.length >= MAX_INVOICE_BCC) {
      return { ok: false, error: `You can add at most ${MAX_INVOICE_BCC} BCC addresses.` };
    }
    seen.add(lower);
    emails.push(emailCheck.data);
  }

  return { ok: true, emails };
}

export async function sendInvoiceEmail(
  orgSlug: string,
  invoiceId: string,
  options?: { to?: string[]; bcc?: string | string[] }
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const bccParse = parseInvoiceBccList(options);
  if (!bccParse.ok) return { error: bccParse.error };
  const bccEmails = bccParse.emails;
  const bcc: string[] | undefined = bccEmails.length ? bccEmails : undefined;

  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId, organizationId: orgId },
    include: {
      customer: {
        include: {
          notificationEmails: true,
          contacts: {
            orderBy: [{ isPrimary: "desc" }, { id: "asc" }],
            include: {
              user: { select: { email: true, name: true } },
            },
          },
        },
      },
      lines: {
        orderBy: { sortOrder: "asc" },
        include: {
          timeEntries: {
            select: {
              loggedDate: true,
              startedAt: true,
              endedAt: true,
              manualMinutes: true,
              createdAt: true,
              description: true,
              user: { select: { name: true, email: true } },
            },
          },
        },
      },
      organization: { include: { settings: true } },
      payments: { orderBy: { paidAt: "desc" } },
    },
  });

  if (!invoice) return { error: "Invoice not found" };

  const whitelist = buildInvoiceEmailRecipientRows({
    email: invoice.customer.email,
    companyName: invoice.customer.companyName,
    contacts: invoice.customer.contacts,
    notificationEmails: invoice.customer.notificationEmails,
  });

  if (whitelist.length === 0) {
    return {
      error:
        "Add invoice routing addresses, a billing email on the customer, or portal contacts with email addresses before sending.",
    };
  }

  if (invoice.status === "VOID") {
    return { error: "Void invoices cannot be emailed." };
  }

  const requestedTo =
    typeof options?.to !== "undefined" && Array.isArray(options.to)
      ? options.to.flatMap((t) => {
          if (typeof t !== "string") return [];
          const v = t.trim();
          return v ? [v] : [];
        })
      : [];

  const MAX_PRIMARY_RECIPIENTS = 35;
  const allowLower = new Set(whitelist.map((w) => w.email.toLowerCase()));
  let toRecipients: string[];

  if (requestedTo.length > 0) {
    const uniq: string[] = [];
    const seen = new Set<string>();
    for (const raw of requestedTo) {
      const k = raw.toLowerCase();
      if (!allowLower.has(k)) {
        return { error: "One or more selected recipients are not allowed for this customer." };
      }
      if (seen.has(k)) continue;
      seen.add(k);
      uniq.push(raw);
    }
    if (uniq.length === 0) return { error: "Select at least one recipient." };
    if (uniq.length > MAX_PRIMARY_RECIPIENTS) {
      return { error: `You can send to at most ${MAX_PRIMARY_RECIPIENTS} recipients at once.` };
    }
    toRecipients = uniq;
  } else {
    const defaultKeys = defaultInvoiceRecipientKeys(whitelist);
    const autoRaw = whitelist.filter((r) => defaultKeys.has(r.key)).map((r) => r.email);
    const seenAuto = new Set<string>();
    toRecipients = [];
    for (const e of autoRaw) {
      const k = e.toLowerCase();
      if (seenAuto.has(k)) continue;
      if (!allowLower.has(k)) continue;
      seenAuto.add(k);
      toRecipients.push(e);
    }
    if (toRecipients.length === 0) {
      return { error: "Select one or more recipients." };
    }
    if (toRecipients.length > MAX_PRIMARY_RECIPIENTS) {
      return { error: `You can send to at most ${MAX_PRIMARY_RECIPIENTS} recipients at once.` };
    }
  }

  const org = invoice.organization;
  const settings = org.settings;
  const customer = invoice.customer;

  const customerName =
    customer.companyName ??
    [customer.firstName, customer.lastName].filter(Boolean).join(" ") ??
    customer.email ??
    "Customer";

  const viewToken = await mintViewToken(invoiceId);
  const publicUrl = await resolveInvoicePublicUrl(viewToken);

  const pdfData: InvoicePdfData = {
    org: invoicePdfOrgBlock(org, settings),
    invoice: {
      number: invoice.number,
      status: invoice.status === "DRAFT" ? "SENT" : invoice.status,
      displayStatus: invoice.status === "DRAFT" || invoice.status === "SENT" ? "UNPAID" : undefined,
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
    lines: invoice.lines.map((l) => ({
      name: l.name,
      description: l.description ?? undefined,
      quantity: l.quantity.toString(),
      qtyType: l.qtyType,
      unitPrice: l.unitPrice.toString(),
      total: l.total.toString(),
    })),
    accentColor: settings?.invoiceAccentColor ?? undefined,
    footerText: settings?.invoiceFooterText ?? undefined,
    numberFormatStyle: normalizeNumberFormatStyle(
      (settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
    ),
    invoicePdfFont: settings?.invoicePdfFont ?? "HELVETICA",
  };

  const pdfBuffer = await generateInvoicePdf(pdfData);

  const vars = await buildInvoiceSentTemplateVars({
    invoice: {
      number: invoice.number,
      currency: invoice.currency,
      total: invoice.total,
      amountPaid: invoice.amountPaid,
      subtotal: invoice.subtotal,
      vat: invoice.vat,
      issuedAt: invoice.issuedAt,
      dueDate: invoice.dueDate,
      periodFrom: invoice.periodFrom,
      periodTo: invoice.periodTo,
      notes: invoice.notes,
      discountValue: invoice.discountValue,
      lines: invoice.lines,
      payments: invoice.payments,
      customer: { email: invoice.customer.email ?? toRecipients[0] ?? "" },
      status: invoice.status,
      paymentMethod: invoice.paymentMethod ?? null,
    },
    org,
    settings,
    pdfData,
    publicUrl,
    customerName,
  });

  const { subject, html, attachments } = await composeEmail(orgId, "invoice.sent", vars, [
    {
      filename: `${invoice.number}.pdf`,
      content: pdfBuffer,
      contentType: "application/pdf",
    },
  ]);

  const trackOpens = settings?.trackInvoiceEmailOpens ?? true;
  const sendKind = invoice.status === "DRAFT" ? ("INITIAL" as const) : ("RESEND" as const);

  const toLowerSet = new Set(toRecipients.map((e) => e.toLowerCase()));
  const bccFiltered = !bcc?.length
    ? []
    : bcc.filter((e) => !toLowerSet.has(e.toLowerCase()));

  let stoppedError: string | undefined;

  for (let i = 0; i < toRecipients.length; i++) {
    const toAddr = toRecipients[i]!;
    const emailSend = await prisma.invoiceEmailSend.create({
      data: {
        organizationId: orgId,
        invoiceId,
        kind: sendKind,
        toEmail: toAddr,
        subject,
      },
    });

    const htmlToSend =
      trackOpens ? await appendInvoiceEmailOpenPixelAsync(html, emailSend.id) : html;

    const result = await sendMail({
      orgId,
      to: toAddr,
      bcc: i === 0 && bccFiltered.length ? bccFiltered : undefined,
      subject,
      html: htmlToSend,
      attachments,
    });

    if (!result.ok) {
      await prisma.invoiceEmailSend.delete({ where: { id: emailSend.id } }).catch(() => {});
      stoppedError = result.error ?? "Failed to send email";
      break;
    }
  }

  if (stoppedError) {
    const hint =
      toRecipients.length > 1
        ? " Some recipients were already emailed; refresh to see tracked sends."
        : "";
    return { error: `${stoppedError}.${hint}` };
  }

  if (invoice.status === "DRAFT") {
    await prisma.invoice.update({
      where: { id: invoiceId },
      data: { status: "SENT", issuedAt: invoice.issuedAt },
    });
  }

  await logAudit({
    organizationId: orgId,
    action: "SEND",
    entityType: "INVOICE",
    entityId: invoiceId,
    metadata: {
      number: invoice.number,
      recipientCount: toRecipients.length,
      bccRecipientCount: bccFiltered.length,
      invoiceEmailKind: sendKind,
    },
  });

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/${invoiceId}`);
  return { success: true };
}

export async function getInvoiceEmailPreview(
  orgSlug: string,
  invoiceId: string
): Promise<{ subject: string; html: string } | { error: string }> {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const session = await auth();
  if (!session?.user?.id) return { error: "Unauthorized" };

  const previewOk = await assertRateLimit(
    `invoice-email-preview:${session.user.id}:${orgId}`,
    60,
    60
  );
  if (!previewOk) {
    return { error: "Too many preview requests. Please wait a minute." };
  }

  const invoice = await prisma.invoice.findFirst({
    where: { id: invoiceId, organizationId: orgId },
    include: {
      customer: true,
      lines: {
        orderBy: { sortOrder: "asc" },
        include: {
          timeEntries: {
            select: {
              loggedDate: true,
              startedAt: true,
              endedAt: true,
              manualMinutes: true,
              createdAt: true,
              description: true,
              user: { select: { name: true, email: true } },
            },
          },
        },
      },
      organization: { include: { settings: true } },
      payments: { orderBy: { paidAt: "desc" } },
    },
  });
  if (!invoice) return { error: "Invoice not found" };

  const org = invoice.organization;
  const settings = org.settings;
  const customer = invoice.customer;
  const customerName =
    customer.companyName ??
    [customer.firstName, customer.lastName].filter(Boolean).join(" ") ??
    customer.email ??
    "Customer";

  const viewToken = await mintViewToken(invoiceId);
  const publicUrl = await resolveInvoicePublicUrl(viewToken);

  const pdfData = await buildInvoicePdfData(invoiceId);
  if (!pdfData) return { error: "Invoice not found" };

  const vars = await buildInvoiceSentTemplateVars({
    invoice: {
      number: invoice.number,
      currency: invoice.currency,
      total: invoice.total,
      amountPaid: invoice.amountPaid,
      subtotal: invoice.subtotal,
      vat: invoice.vat,
      issuedAt: invoice.issuedAt,
      dueDate: invoice.dueDate,
      periodFrom: invoice.periodFrom,
      periodTo: invoice.periodTo,
      notes: invoice.notes,
      discountValue: invoice.discountValue,
      lines: invoice.lines,
      payments: invoice.payments,
      customer: { email: invoice.customer.email },
      status: invoice.status,
      paymentMethod: invoice.paymentMethod ?? null,
    },
    org,
    settings,
    pdfData,
    publicUrl,
    customerName,
  });

  const { subject, html } = await composeEmail(orgId, "invoice.sent", vars);
  return { subject, html: sanitizeEmailPreviewHtml(html) };
}

export async function updateInvoiceStatus(
  orgSlug: string,
  invoiceId: string,
  status: InvoiceStatus
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  if (status === "REFUNDED" || status === "CHARGEBACK") {
    return { error: "This status is derived from payments and Stripe disputes" };
  }

  await prisma.invoice.update({
    where: { id: invoiceId, organizationId: orgId },
    data: { status },
  });

  await logAudit({
    organizationId: orgId,
    action: "STATUS_CHANGE",
    entityType: "INVOICE",
    entityId: invoiceId,
    metadata: { status },
  });

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/${invoiceId}`);
  return { success: true };
}

// ─── Recurring Invoice Rules ──────────────────────────────────────────────────

export type RecurringRuleInput = RecurringRuleCreateInput;

export async function createRecurringRule(orgSlug: string, input: RecurringRuleInput) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = recurringRuleCreateSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const rule = await prisma.recurringInvoiceRule.create({
    data: {
      organizationId: orgId,
      customerId: parsed.data.customerId,
      interval: parsed.data.interval,
      nextRunAt: new Date(parsed.data.nextRunAt),
      endAt: parsed.data.endAt ? new Date(parsed.data.endAt) : null,
      templateData: parsed.data.templateData,
    },
  });

  await logAudit({
    organizationId: orgId,
    action: "CREATE",
    entityType: "RECURRING_RULE",
    entityId: rule.id,
    metadata: { customerId: parsed.data.customerId, interval: parsed.data.interval },
  });

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/recurring`);
  return { success: true };
}

export async function updateRecurringRuleTemplate(
  orgSlug: string,
  ruleId: string,
  input: unknown
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = recurringRuleTemplateDataSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const linesForStorage = parsed.data.lines.map((l, i) => ({
    name: l.name,
    description: l.description,
    quantity: l.quantity,
    qtyType: l.qtyType,
    unitPrice: l.unitPrice,
    sortOrder: l.sortOrder ?? i,
  }));

  const templatePayload = {
    ...parsed.data,
    lines: linesForStorage,
  };

  const updated = await prisma.recurringInvoiceRule.updateMany({
    where: { id: ruleId, organizationId: orgId },
    data: { templateData: templatePayload },
  });

  if (updated.count === 0) return { error: "Rule not found" };

  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "RECURRING_RULE",
    entityId: ruleId,
    metadata: { currency: parsed.data.currency, lineCount: parsed.data.lines.length },
  });

  revalidatePath(`/${orgSlug}/invoices/recurring`);
  revalidatePath(`/${orgSlug}/invoices/recurring/${ruleId}/edit`);
  return { success: true };
}

export async function updateRecurringRule(
  orgSlug: string,
  ruleId: string,
  active: boolean
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const updated = await prisma.recurringInvoiceRule.updateMany({
    where: { id: ruleId, organizationId: orgId },
    data: { active },
  });

  if (updated.count === 0) return { error: "Rule not found" };

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/recurring`);
  return { success: true };
}

export async function deleteRecurringRule(orgSlug: string, ruleId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const deleted = await prisma.recurringInvoiceRule.deleteMany({
    where: { id: ruleId, organizationId: orgId },
  });

  if (deleted.count === 0) return { error: "Rule not found" };

  revalidatePath(`/${orgSlug}/invoices`);
  revalidatePath(`/${orgSlug}/invoices/recurring`);
  return { success: true };
}

// ─── Internal helper for workers ─────────────────────────────────────────────

function invoicePdfOrgBlock(org: { name: string }, settings: OrgSettings | null): InvoicePdfData["org"] {
  return {
    name: settings?.companyName ?? org.name,
    address: buildCompanyAddress(settings ?? {}),
    vat: settings?.companyVat,
    logoUrl: settings?.companyLogoUrl ?? undefined,
    billingEmail: settings?.smtpFrom?.trim() || null,
    phoneContact: settings?.companyPhone?.trim() || null,
  };
}

export async function buildInvoicePdfData(
  invoiceId: string
): Promise<InvoicePdfData | null> {
  const invoice = await prisma.invoice.findUnique({
    where: { id: invoiceId },
    include: {
      customer: true,
      lines: { orderBy: { sortOrder: "asc" } },
      organization: { include: { settings: true } },
    },
  });

  if (!invoice) return null;

  const org = invoice.organization;
  const settings = org.settings;
  const customer = invoice.customer;

  const customerName =
    customer.companyName ??
    [customer.firstName, customer.lastName].filter(Boolean).join(" ") ??
    customer.email ??
    "Customer";

  return {
    org: invoicePdfOrgBlock(org, settings),
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
    lines: invoice.lines.map((l) => ({
      name: l.name,
      description: l.description ?? undefined,
      quantity: l.quantity.toString(),
      qtyType: l.qtyType,
      unitPrice: l.unitPrice.toString(),
      total: l.total.toString(),
    })),
    accentColor: settings?.invoiceAccentColor ?? undefined,
    footerText: settings?.invoiceFooterText ?? undefined,
    numberFormatStyle: normalizeNumberFormatStyle(
      (settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
    ),
    invoicePdfFont: settings?.invoicePdfFont ?? "HELVETICA",
  };
}

// ─── Billable Tasks ───────────────────────────────────────────────────────────

export type BillableTask = {
  id: string;
  title: string;
  projectId: string;
  projectName: string;
  hourlyRate: string;
  currency: string;
  unbilledHours: number;
};

export async function getBillableTasks(
  orgSlug: string,
  customerId: string,
  projectId?: string | null
): Promise<{ tasks?: BillableTask[]; error?: string }> {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const tasks = await prisma.task.findMany({
    where: {
      organizationId: orgId,
      project: {
        customerId,
        ...(projectId ? { id: projectId } : {}),
      },
      timeEntries: { some: { billed: false } },
    },
    include: {
      project: { select: { id: true, name: true, hourlyRate: true, currency: true } },
      timeEntries: {
        where: { billed: false },
        select: { manualMinutes: true, startedAt: true, endedAt: true },
      },
    },
  });

  return {
    tasks: tasks.map((t) => ({
      id: t.id,
      title: t.title,
      projectId: t.project.id,
      projectName: t.project.name,
      hourlyRate: (t.hourlyRate ?? t.project.hourlyRate)?.toString() ?? "0",
      currency: t.project.currency,
      unbilledHours: t.timeEntries.reduce((sum, te) => {
        if (te.manualMinutes) return sum + te.manualMinutes / 60;
        if (te.startedAt && te.endedAt) {
          return sum + (new Date(te.endedAt).getTime() - new Date(te.startedAt).getTime()) / 3_600_000;
        }
        return sum;
      }, 0),
    })),
  };
}

function escHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// ─── Invoice line metadata (task/time snapshot) ───────────────────────────────

export interface InvoiceLineMetadata {
  taskTitle: string;
  taskDescription: string | null;
  projectName: string;
  projectId: string;
  entries: Array<{
    date: string;       // YYYY-MM-DD
    minutes: number;
    userName: string;
    description: string | null;
  }>;
}

function prismaJsonForLineMetadata(
  taskId: string | null | undefined,
  taskMetaMap: Map<string, InvoiceLineMetadata>
): Prisma.NullableJsonNullValueInput | Prisma.InputJsonValue | undefined {
  if (!taskId) return undefined;
  const snap = taskMetaMap.get(taskId);
  if (snap == null) return Prisma.DbNull;
  return snap as unknown as Prisma.InputJsonValue;
}

/**
 * Builds a per-task snapshot of unbilled time entries at the moment of billing.
 * Called before creating or updating invoice lines so the data is frozen in metadata.
 */
async function snapshotTaskMetadata(
  taskIds: string[]
): Promise<Map<string, InvoiceLineMetadata>> {
  if (taskIds.length === 0) return new Map();

  const tasks = await prisma.task.findMany({
    where: { id: { in: taskIds } },
    select: {
      id: true,
      title: true,
      description: true,
      project: { select: { id: true, name: true } },
      timeEntries: {
        where: { billed: false },
        select: {
          loggedDate: true,
          startedAt: true,
          endedAt: true,
          manualMinutes: true,
          createdAt: true,
          description: true,
          user: { select: { name: true, email: true } },
        },
      },
    },
  });

  const map = new Map<string, InvoiceLineMetadata>();
  for (const task of tasks) {
    const entries: InvoiceLineMetadata["entries"] =
      mergeInvoiceEntriesSameCalendarDayAndUser(
        task.timeEntries.map((te) => {
          let minutes = te.manualMinutes ?? 0;
          if (!minutes && te.startedAt && te.endedAt) {
            minutes = Math.round(
              (new Date(te.endedAt).getTime() - new Date(te.startedAt).getTime()) / 60000
            );
          }
          return {
            date: invoiceTimeEntryCalendarIsoDate(te),
            minutes,
            userName: te.user.name ?? te.user.email ?? "Unknown",
            description: te.description ?? null,
          };
        })
      );
    map.set(task.id, {
      taskTitle: task.title,
      taskDescription: task.description ?? null,
      projectName: task.project.name,
      projectId: task.project.id,
      entries,
    });
  }
  return map;
}

type LineSummary = {
  name: string;
  description?: string | null;
  metadata: unknown;
  taskId?: string | null;
  quantity: { toString(): string };
  qtyType: string;
  unitPrice: { toString(): string };
  total: { toString(): string };
  timeEntries?: Array<{
    loggedDate: Date | null;
    startedAt: Date | null;
    endedAt: Date | null;
    manualMinutes: number | null;
    createdAt: Date;
    description: string | null;
    user: { name: string | null; email: string | null };
  }>;
};

type TaskLookup = Map<
  string,
  {
    taskTitle: string;
    taskDescription: string | null;
    projectName: string;
  }
>;

async function buildTaskLookup(lines: LineSummary[]): Promise<TaskLookup> {
  const taskIds = Array.from(
    new Set(lines.map((line) => line.taskId).filter((taskId): taskId is string => Boolean(taskId)))
  );
  if (taskIds.length === 0) return new Map();

  const tasks = await prisma.task.findMany({
    where: { id: { in: taskIds } },
    select: {
      id: true,
      title: true,
      description: true,
      project: { select: { name: true } },
    },
  });

  return new Map(
    tasks.map((task) => [
      task.id,
      {
        taskTitle: task.title,
        taskDescription: task.description ?? null,
        projectName: task.project.name,
      },
    ])
  );
}

function minutesFromEntry(entry: {
  startedAt: Date | null;
  endedAt: Date | null;
  manualMinutes: number | null;
}): number {
  let minutes = entry.manualMinutes ?? 0;
  if (!minutes && entry.startedAt && entry.endedAt) {
    minutes = Math.round(
      (new Date(entry.endedAt).getTime() - new Date(entry.startedAt).getTime()) / 60000
    );
  }
  return minutes;
}

function mapLineEntriesFromTimeEntries(
  timeEntries: NonNullable<LineSummary["timeEntries"]>
): InvoiceLineMetadata["entries"] {
  return timeEntries.map((entry) => ({
    date: invoiceTimeEntryCalendarIsoDate(entry),
    minutes: minutesFromEntry(entry),
    userName: entry.user.name ?? entry.user.email ?? "Unknown",
    description: entry.description ?? null,
  }));
}

function resolveLineMetadata(
  line: LineSummary,
  taskLookup: TaskLookup
): InvoiceLineMetadata | null {
  const entriesHydrated =
    line.timeEntries && line.timeEntries.length > 0
      ? mapLineEntriesFromTimeEntries(line.timeEntries)
      : null;

  const metadataCandidate = line.metadata as Partial<InvoiceLineMetadata> | null;
  if (
    metadataCandidate &&
    typeof metadataCandidate.taskTitle === "string" &&
    typeof metadataCandidate.projectName === "string" &&
    Array.isArray(metadataCandidate.entries)
  ) {
    const snap = metadataCandidate as InvoiceLineMetadata;
    return {
      ...snap,
      entries: mergeInvoiceEntriesSameCalendarDayAndUser(entriesHydrated ?? snap.entries),
    };
  }

  if (!line.taskId) return null;
  const task = taskLookup.get(line.taskId);
  if (!task) return null;

  return {
    taskTitle: task.taskTitle,
    taskDescription: task.taskDescription,
    projectName: task.projectName,
    projectId: "",
    entries: mergeInvoiceEntriesSameCalendarDayAndUser(entriesHydrated ?? []),
  };
}

function formatLineQtyForEmail(line: LineSummary): string {
  const q = parseFloat(line.quantity.toString());
  if (Number.isNaN(q)) return line.quantity.toString();
  if (line.qtyType === "HOURS" || line.qtyType === "QTY_HOURS") {
    return `${q.toFixed(2)}h`;
  }
  return line.quantity.toString();
}

function invoiceHasBilledTasks(lines: LineSummary[], taskLookup: TaskLookup): boolean {
  return lines.some((line) => resolveLineMetadata(line, taskLookup) !== null);
}

/**
 * Line items for non–task-based invoices (Item / Qty / Rate / Amount).
 */
function buildInvoiceGenericLineItemsTableHtml(
  lines: LineSummary[],
  currency: Currency,
  numberFormatStyle: NumberFormatStyle,
  tableMarginTop: string
): string {
  if (lines.length === 0) return "";

  let html =
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;font-size:13px;margin:${tableMarginTop};color:#1e293b">` +
    `<thead><tr style="border-bottom:1px solid #e2e8f0">` +
    `<th style="text-align:left;padding:6px 0;color:#64748b;font-weight:600;font-size:11px">Item</th>` +
    `<th style="text-align:right;padding:6px 0;color:#64748b;font-weight:600;font-size:11px">Qty</th>` +
    `<th style="text-align:right;padding:6px 8px;color:#64748b;font-weight:600;font-size:11px">Rate</th>` +
    `<th style="text-align:right;padding:6px 0;color:#64748b;font-weight:600;font-size:11px">Amount</th>` +
    `</tr></thead><tbody>`;

  for (const line of lines) {
    const col1 =
      `<strong>${escHtml(line.name)}</strong>` +
      (line.description?.trim()
        ? `<br/><span style="font-size:11px;color:#64748b">${escHtml(line.description)}</span>`
        : "");
    const qty = formatLineQtyForEmail(line);
    const rate = formatCurrency(line.unitPrice.toString(), currency, numberFormatStyle);
    const amount = formatCurrency(line.total.toString(), currency, numberFormatStyle);

    html +=
      `<tr style="border-bottom:1px solid #f1f5f9">` +
      `<td style="padding:8px 0;vertical-align:top">${col1}</td>` +
      `<td style="padding:8px 0;text-align:right;color:#64748b;vertical-align:top;white-space:nowrap">${escHtml(qty)}</td>` +
      `<td style="padding:8px 8px;text-align:right;color:#64748b;vertical-align:top;white-space:nowrap">${escHtml(rate)}</td>` +
      `<td style="padding:8px 0;text-align:right;font-weight:600;color:#1e293b;vertical-align:top;white-space:nowrap">${escHtml(amount)}</td>` +
      `</tr>`;
  }

  html += `</tbody></table>`;
  return html;
}

/**
 * Summary block for invoice.sent: task/time lines use Task / Hours / Rate / Amount (+ optional time-entry
 * rows). Otherwise Item / Qty / Rate / Amount plus invoiced period when set.
 * Used as {{invoice.lineItemsTableHtml}}.
 */
function buildInvoiceLineItemsTableHtml(
  lines: LineSummary[],
  currency: Currency,
  numberFormatStyle: NumberFormatStyle,
  taskLookup: TaskLookup,
  periodFrom: Date | null,
  periodTo: Date | null
): string {
  if (lines.length === 0) return "";

  if (!invoiceHasBilledTasks(lines, taskLookup)) {
    const periodHtml = formatInvoicePeriodSummaryHtml(periodFrom, periodTo);
    const tableTop = periodHtml ? "0" : "16px 0 0";
    return periodHtml + buildInvoiceGenericLineItemsTableHtml(lines, currency, numberFormatStyle, tableTop);
  }

  let html =
    `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;font-size:13px;margin:16px 0 0;color:#1e293b">` +
    `<thead><tr style="border-bottom:1px solid #e2e8f0">` +
    `<th style="text-align:left;padding:6px 0;color:#64748b;font-weight:600;font-size:11px">Task</th>` +
    `<th style="text-align:right;padding:6px 0;color:#64748b;font-weight:600;font-size:11px">Hours</th>` +
    `<th style="text-align:right;padding:6px 8px;color:#64748b;font-weight:600;font-size:11px">Rate</th>` +
    `<th style="text-align:right;padding:6px 0;color:#64748b;font-weight:600;font-size:11px">Amount</th>` +
    `</tr></thead><tbody>`;

  for (const line of lines) {
    const meta = resolveLineMetadata(line, taskLookup);
    const hoursOrQty = formatLineQtyForEmail(line);
    const rate = formatCurrency(line.unitPrice.toString(), currency, numberFormatStyle);
    const amount = formatCurrency(line.total.toString(), currency, numberFormatStyle);

    const col1 = meta
      ? `<strong>${escHtml(meta.taskTitle)}</strong>` +
        `<br/><span style="font-size:11px;color:#94a3b8">${escHtml(meta.projectName)}</span>` +
        (meta.taskDescription
          ? `<br/><span style="font-size:11px;color:#64748b">${escHtml(meta.taskDescription)}</span>`
          : "")
      : `<strong>${escHtml(line.name)}</strong>` +
        (line.description?.trim()
          ? `<br/><span style="font-size:11px;color:#64748b">${escHtml(line.description)}</span>`
          : "");

    html +=
      `<tr style="border-bottom:1px solid #f1f5f9">` +
      `<td style="padding:8px 0;vertical-align:top">${col1}</td>` +
      `<td style="padding:8px 0;text-align:right;color:#64748b;vertical-align:top;white-space:nowrap">${escHtml(hoursOrQty)}</td>` +
      `<td style="padding:8px 8px;text-align:right;color:#64748b;vertical-align:top;white-space:nowrap">${escHtml(rate)}</td>` +
      `<td style="padding:8px 0;text-align:right;font-weight:600;color:#1e293b;vertical-align:top;white-space:nowrap">${escHtml(amount)}</td>` +
      `</tr>`;

    if (meta) {
      for (const entry of meta.entries) {
        const entryHours = (entry.minutes / 60).toFixed(2);
        html +=
          `<tr style="background:#f8fafc">` +
          `<td colspan="2" style="padding:4px 0 4px 14px;font-size:11px;color:#94a3b8">` +
          `${escHtml(entry.date)} - ${escHtml(entry.userName)}` +
          (entry.description ? ` - ${escHtml(entry.description)}` : "") +
          `</td>` +
          `<td style="padding:4px 12px 4px 0;text-align:right;font-size:11px;color:#94a3b8">${entryHours}h</td>` +
          `<td></td>` +
          `</tr>`;
      }
    }
  }

  html += `</tbody></table>`;
  return html;
}

/**
 * Renders an HTML table of task lines with per-entry drill-down rows.
 * Used as {{invoice.tasksHtml}} in email templates.
 */
function buildTasksHtml(
  lines: LineSummary[],
  currency: string,
  numberFormatStyle: NumberFormatStyle,
  taskLookup: TaskLookup
): string {
  const taskLines = lines.filter((line) => resolveLineMetadata(line, taskLookup));
  if (taskLines.length === 0) return "";

  let html =
    `<table width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;font-size:13px;margin:16px 0">` +
    `<thead><tr style="border-bottom:2px solid #e2e8f0">` +
    `<th style="text-align:left;padding:8px 0;color:#64748b;font-weight:600">Task</th>` +
    `<th style="text-align:right;padding:8px 0;color:#64748b;font-weight:600">Hours</th>` +
    `<th style="text-align:right;padding:8px 0;color:#64748b;font-weight:600">Rate</th>` +
    `<th style="text-align:right;padding:8px 0;color:#64748b;font-weight:600">Amount</th>` +
    `</tr></thead><tbody>`;

  for (const line of taskLines) {
    const meta = resolveLineMetadata(line, taskLookup);
    if (!meta) continue;
    const hours = parseFloat(line.quantity.toString());
    const rate = parseFloat(line.unitPrice.toString());
    const amount = parseFloat(line.total.toString());

    html +=
      `<tr style="border-bottom:1px solid #f1f5f9">` +
      `<td style="padding:10px 0;color:#1e293b;vertical-align:top">` +
      `<strong>${escHtml(meta.taskTitle)}</strong>` +
      `<br/><span style="font-size:11px;color:#94a3b8">${escHtml(meta.projectName)}</span>` +
      (meta.taskDescription
        ? `<br/><span style="font-size:11px;color:#64748b">${escHtml(meta.taskDescription)}</span>`
        : "") +
      `</td>` +
      `<td style="padding:10px 0;text-align:right;color:#64748b;vertical-align:top">${hours.toFixed(2)}h</td>` +
      `<td style="padding:10px 8px;text-align:right;color:#64748b;vertical-align:top">${formatCurrency(rate, currency as Currency, numberFormatStyle)}</td>` +
      `<td style="padding:10px 0;text-align:right;font-weight:600;color:#1e293b;vertical-align:top">${formatCurrency(amount, currency as Currency, numberFormatStyle)}</td>` +
      `</tr>`;

    for (const entry of meta.entries) {
      const entryHours = (entry.minutes / 60).toFixed(2);
      html +=
        `<tr style="background:#f8fafc">` +
        `<td colspan="2" style="padding:3px 0 3px 16px;font-size:11px;color:#94a3b8">` +
        `${escHtml(entry.date)} - ${escHtml(entry.userName)}` +
        (entry.description ? ` - ${escHtml(entry.description)}` : "") +
        `</td>` +
        `<td style="padding:3px 0;text-align:right;font-size:11px;color:#94a3b8">${entryHours}h</td>` +
        `<td></td>` +
        `</tr>`;
    }
  }

  html += `</tbody></table>`;
  return html;
}

/**
 * Renders a plain-text bullet list of billed tasks.
 * Used as {{invoice.tasksBrief}} in email templates.
 */
function buildTasksBrief(
  lines: LineSummary[],
  currency: string,
  numberFormatStyle: NumberFormatStyle,
  taskLookup: TaskLookup
): string {
  const taskLines = lines.filter((line) => resolveLineMetadata(line, taskLookup));
  if (taskLines.length === 0) return "";

  return taskLines
    .map((line) => {
      const meta = resolveLineMetadata(line, taskLookup);
      if (!meta) return "";
      const hours = parseFloat(line.quantity.toString()).toFixed(2);
      const rate = formatCurrency(parseFloat(line.unitPrice.toString()), currency as Currency, numberFormatStyle);
      const amount = formatCurrency(parseFloat(line.total.toString()), currency as Currency, numberFormatStyle);
      return `• ${meta.taskTitle} (${meta.projectName}): ${hours}h @ ${rate}/h = ${amount}`;
    })
    .filter(Boolean)
    .join("\n");
}
