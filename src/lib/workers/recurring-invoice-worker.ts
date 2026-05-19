import { Queue, Worker } from "bullmq";
import { prisma } from "@/lib/db/prisma";
import { Currency, InvoiceTemplate, PaymentMethod } from "@prisma/client";
import { addDays, addWeeks } from "date-fns";
import { getNextInvoiceNumber } from "@/lib/invoices/doc-number";
import {
  MAX_DOC_NUMBER_ATTEMPTS,
  isInvoiceOrgNumberUniqueConflict,
} from "@/lib/invoices/prisma-doc-number-unique";
import { computeTotals } from "@/lib/invoices/compute-totals";
import { getFxRate } from "@/lib/invoices/fx-rate";
import { computeAutoBilledPeriodFromIssueDate } from "@/lib/invoices/auto-billed-period-from-issue";
import { createBullmqConnection } from "./redis-connection";

export const recurringInvoiceQueue = new Queue("recurring-invoices", {
  connection: createBullmqConnection(),
});

export async function scheduleRecurringInvoiceWorker() {
  const isProd = process.env.NODE_ENV === "production";
  const cron = process.env.RECURRING_INVOICE_CRON?.trim();

  if (!isProd) {
    const existing = await recurringInvoiceQueue.getRepeatableJobs();
    for (const job of existing) {
      await recurringInvoiceQueue.removeRepeatableByKey(job.key);
    }
  }

  if (cron) {
    await recurringInvoiceQueue.add(
      "run",
      {},
      { repeat: { pattern: cron }, jobId: "recurring-invoices-cron" }
    );
    console.log(`[Workers] Recurring invoices scheduled: cron "${cron}"`);
  } else if (isProd) {
    await recurringInvoiceQueue.add(
      "run",
      {},
      { repeat: { pattern: "0 7 * * *" }, jobId: "recurring-invoices-daily" }
    );
    console.log("[Workers] Recurring invoices scheduled: daily at 07:00 (production)");
  } else {
    await recurringInvoiceQueue.add(
      "run",
      {},
      { repeat: { every: 60_000 }, jobId: "recurring-invoices-dev-every-60s" }
    );
    console.log("[Workers] Recurring invoices scheduled: every 60 seconds (non-production)");
    await recurringInvoiceQueue.add("run", {}, { removeOnComplete: 25, removeOnFail: 10 });
    console.log("[Workers] Recurring invoices: queued one immediate run");
  }
}

async function getNextRunAt(currentRunAt: Date, interval: string): Promise<Date> {
  switch (interval) {
    case "WEEKLY":
      return addWeeks(currentRunAt, 1);
    case "BIWEEKLY":
      return addWeeks(currentRunAt, 2);
    case "MONTHLY":
    default: {
      const next = new Date(currentRunAt);
      next.setMonth(next.getMonth() + 1);
      return next;
    }
  }
}

type RecurringTemplateData = {
  currency?: Currency;
  vatRate?: string;
  vatIncluded?: boolean;
  discountType?: string;
  discountValue?: string;
  discountBeforeTax?: boolean;
  notes?: string;
  termsAndConditions?: string;
  paymentMethod?: PaymentMethod;
  template?: InvoiceTemplate;
  daysUntilDue?: number;
  billedPeriodMode?: "NONE" | "AUTO_BY_ISSUE_DATE";
  lines: Array<{
    name?: string;
    description?: string;
    quantity: string;
    unitPrice: string;
    sortOrder?: number;
    qtyType?: string;
  }>;
};

export function startRecurringInvoiceWorker() {
  const worker = new Worker(
    "recurring-invoices",
    async () => {
      const now = new Date();

      const rules = await prisma.recurringInvoiceRule.findMany({
        where: { active: true, nextRunAt: { lte: now } },
        include: { organization: { include: { settings: true } } },
      });

      for (const rule of rules) {
        const templateData = rule.templateData as RecurringTemplateData;

        const lines = templateData.lines ?? [];
        const vatRateRaw =
          templateData.vatRate ?? rule.organization.settings?.vatRate?.toString() ?? "0";
        const vatRate = parseFloat(vatRateRaw);

        const vatIncluded =
          templateData.vatIncluded ?? rule.organization.settings?.defaultVatIncluded ?? false;
        const discountType = templateData.discountType ?? "NONE";
        const discountValue = parseFloat(templateData.discountValue ?? "0") || 0;
        const discountBeforeTax = templateData.discountBeforeTax ?? true;

        const { subtotal, discount, vat, total } = computeTotals(
          lines,
          vatRate,
          vatIncluded,
          discountType,
          discountValue,
          discountBeforeTax
        );

        const dueDate = addDays(now, templateData.daysUntilDue ?? 30);

        const orgTemplate = rule.organization.settings?.defaultInvoiceTemplate ?? "CLASSIC";
        const resolvedTemplate = templateData.template ?? orgTemplate;

        const currency = templateData.currency ?? "EUR";
        const fxRate = await getFxRate(rule.organizationId, currency);

        const autoBilledPeriod =
          rule.interval === "MONTHLY" &&
          templateData.billedPeriodMode === "AUTO_BY_ISSUE_DATE";
        const billedPeriod = autoBilledPeriod
          ? computeAutoBilledPeriodFromIssueDate(now)
          : null;

        try {
          let allocatedNumber: string | undefined;
          alloc: for (let attempt = 0; attempt < MAX_DOC_NUMBER_ATTEMPTS; attempt++) {
            const number = await getNextInvoiceNumber(rule.organizationId);
            try {
              await prisma.invoice.create({
                data: {
                  organizationId: rule.organizationId,
                  customerId: rule.customerId,
                  number,
                  currency,
                  fxRateToOrgCurrency: fxRate,
                  subtotal,
                  vat: vat,
                  vatRate,
                  total,
                  vatIncluded,
                  discount,
                  discountType,
                  discountValue:
                    templateData.discountValue ??
                    (discountType === "NONE" ? "0" : String(discountValue)),
                  discountBeforeTax,
                  notes: templateData.notes,
                  termsAndConditions: templateData.termsAndConditions,
                  paymentMethod: templateData.paymentMethod,
                  issuedAt: now,
                  dueDate,
                  ...(billedPeriod
                    ? { periodFrom: billedPeriod.periodFrom, periodTo: billedPeriod.periodTo }
                    : {}),
                  recurringRuleId: rule.id,
                  template: resolvedTemplate,
                  lines: {
                    create: lines.map((l, i) => {
                      const qty = l.quantity;
                      const unit = l.unitPrice;
                      const lineTot = (parseFloat(qty) * parseFloat(unit)).toFixed(2);
                      const name = [l.name, l.description].find((s) => s && String(s).trim()) ?? "Item";
                      return {
                        name: String(name).trim(),
                        description: l.description ?? null,
                        quantity: qty,
                        unitPrice: unit,
                        total: lineTot,
                        sortOrder: l.sortOrder ?? i,
                        qtyType: l.qtyType === "HOURS" || l.qtyType === "QTY_HOURS" ? l.qtyType : "QTY",
                      };
                    }),
                  },
                },
              });
              allocatedNumber = number;
              break alloc;
            } catch (e) {
              if (isInvoiceOrgNumberUniqueConflict(e)) continue;
              throw e;
            }
          }

          if (!allocatedNumber) {
            throw new Error(
              `Could not allocate a unique invoice number for recurring rule ${rule.id} after ${MAX_DOC_NUMBER_ATTEMPTS} attempts`
            );
          }

          const nextRunAt = await getNextRunAt(rule.nextRunAt, rule.interval);
          const isExpired = rule.endAt && nextRunAt > rule.endAt;

          await prisma.recurringInvoiceRule.update({
            where: { id: rule.id },
            data: {
              nextRunAt,
              active: !isExpired,
            },
          });

          console.log(`[RecurringInvoice] Created ${allocatedNumber} for org ${rule.organizationId}`);
        } catch (err) {
          console.error(`[RecurringInvoice] Failed for rule ${rule.id}:`, err);
        }
      }
    },
    { connection: createBullmqConnection() }
  );

  worker.on("active", (job) => {
    console.log("[RecurringInvoice] Job picked up:", job.id, job.name);
  });
  worker.on("completed", () => console.log("[RecurringInvoice] Run completed"));
  worker.on("failed", (job, err) => console.error("[RecurringInvoice] Job failed:", job?.id, err));

  return worker;
}
