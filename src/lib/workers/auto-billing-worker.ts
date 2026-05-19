import { Queue, Worker } from "bullmq";
import { prisma } from "@/lib/db/prisma";
import { BillingMode, AutoInvoiceCycle, Prisma } from "@prisma/client";
import type { InvoiceLineMetadata } from "@/lib/actions/invoices";
import { getNextInvoiceNumber } from "@/lib/invoices/doc-number";
import {
  MAX_DOC_NUMBER_ATTEMPTS,
  isInvoiceOrgNumberUniqueConflict,
} from "@/lib/invoices/prisma-doc-number-unique";
import {
  invoiceTimeEntryCalendarIsoDate,
  mergeInvoiceEntriesSameCalendarDayAndUser,
} from "@/lib/filters/time-entry-date-range";
import { createBullmqConnection } from "./redis-connection";

export const autoBillingQueue = new Queue("auto-billing", { connection: createBullmqConnection() });

export async function scheduleAutoBillingWorker() {
  await autoBillingQueue.add(
    "run",
    {},
    {
      repeat: { pattern: "0 9 * * *" },
      jobId: "daily-auto-billing",
    }
  );
}

interface SemiMonthlyConfig {
  splitDay: number;
  emitDay1: number;
  emitDay2: number;
}

function resolveSemiMonthlyConfig(
  project: {
    semiMonthlyPeriodSplitDay: number | null;
    semiMonthlyEmitDay1: number | null;
    semiMonthlyEmitDay2: number | null;
  },
  orgSettings: {
    semiMonthlyPeriodSplitDay: number;
    semiMonthlyEmitDay1: number;
    semiMonthlyEmitDay2: number;
  } | null
): SemiMonthlyConfig {
  return {
    splitDay: project.semiMonthlyPeriodSplitDay ?? orgSettings?.semiMonthlyPeriodSplitDay ?? 15,
    emitDay1: project.semiMonthlyEmitDay1 ?? orgSettings?.semiMonthlyEmitDay1 ?? 16,
    emitDay2: project.semiMonthlyEmitDay2 ?? orgSettings?.semiMonthlyEmitDay2 ?? 1,
  };
}

/**
 * Returns true if today is a billing trigger day for the project's cycle.
 * For SEMI_MONTHLY also returns the period date range to collect time entries from.
 */
function matchesCycle(
  today: Date,
  autoInvoiceDay: number | null,
  cycle: AutoInvoiceCycle,
  semiMonthlyConfig?: SemiMonthlyConfig
): { matches: boolean; periodFrom?: Date; periodTo?: Date } {
  const day = today.getDate();
  const dow = today.getDay();

  switch (cycle) {
    case "MONTHLY":
      return { matches: autoInvoiceDay !== null && day === autoInvoiceDay };

    case "WEEKLY":
      return { matches: autoInvoiceDay !== null && dow === (autoInvoiceDay % 7) };

    case "BIWEEKLY": {
      if (autoInvoiceDay !== null && dow !== (autoInvoiceDay % 7)) return { matches: false };
      const startOfYear = new Date(today.getFullYear(), 0, 1);
      const weekNumber = Math.floor(
        (today.getTime() - startOfYear.getTime()) / (7 * 24 * 60 * 60 * 1000)
      );
      return { matches: weekNumber % 2 === 0 };
    }

    case "SEMI_MONTHLY": {
      if (!semiMonthlyConfig) return { matches: false };
      const { splitDay, emitDay1, emitDay2 } = semiMonthlyConfig;

      if (day === emitDay1) {
        // Emit invoice covering period 1: 1st → splitDay of current month
        const periodFrom = new Date(today.getFullYear(), today.getMonth(), 1, 0, 0, 0, 0);
        const periodTo = new Date(today.getFullYear(), today.getMonth(), splitDay, 23, 59, 59, 999);
        return { matches: true, periodFrom, periodTo };
      }

      if (day === emitDay2) {
        // Emit invoice covering period 2: (splitDay+1) → end of previous month
        const prevMonthYear = today.getMonth() === 0 ? today.getFullYear() - 1 : today.getFullYear();
        const prevMonth = today.getMonth() === 0 ? 11 : today.getMonth() - 1;
        const periodFrom = new Date(prevMonthYear, prevMonth, splitDay + 1, 0, 0, 0, 0);
        // Last moment of previous month
        const periodTo = new Date(today.getFullYear(), today.getMonth(), 0, 23, 59, 59, 999);
        return { matches: true, periodFrom, periodTo };
      }

      return { matches: false };
    }

    default:
      return { matches: false };
  }
}

export function startAutoBillingWorker() {
  const worker = new Worker(
    "auto-billing",
    async () => {
      const today = new Date();

      const projects = await prisma.project.findMany({
        where: {
          autoInvoice: true,
          status: "ACTIVE",
        },
        include: {
          organization: { include: { settings: true } },
          customer: true,
          tasks: true,
        },
      });

      for (const project of projects) {
        const orgSettings = project.organization.settings;

        let periodFrom: Date | undefined;
        let periodTo: Date | undefined;

        if (project.autoInvoiceCycle === "SEMI_MONTHLY") {
          const config = resolveSemiMonthlyConfig(project, orgSettings);
          const result = matchesCycle(today, project.autoInvoiceDay, project.autoInvoiceCycle, config);
          if (!result.matches) continue;
          periodFrom = result.periodFrom;
          periodTo = result.periodTo;
        } else {
          const result = matchesCycle(today, project.autoInvoiceDay, project.autoInvoiceCycle);
          if (!result.matches) continue;
        }

        // Build the time entry filter. For SEMI_MONTHLY, scope to the period window.
        const timeEntryWhere = periodFrom && periodTo
          ? {
              billed: false,
              endedAt: { not: null as null },
              manualMinutes: null as null,
              startedAt: { gte: periodFrom, lte: periodTo },
            }
          : {
              billed: false,
              endedAt: { not: null as null },
              manualMinutes: null as null,
            };

        // Fetch time entries for all tasks in the project
        const unbilledEntries: Array<{
          id: string;
          taskId: string;
          taskTitle: string;
          taskDescription: string | null;
          minutes: number;
          hourlyRate: number;
          date: string;
          userName: string;
          entryDescription: string | null;
        }> = [];

        for (const task of project.tasks) {
          const entries = await prisma.timeEntry.findMany({
            where: { taskId: task.id, ...timeEntryWhere },
            include: { user: { select: { name: true, email: true } } },
          });

          for (const entry of entries) {
            const minutes =
              entry.manualMinutes ??
              (entry.startedAt && entry.endedAt
                ? Math.round(
                    (entry.endedAt.getTime() - entry.startedAt.getTime()) / 60000
                  )
                : 0);

            if (minutes <= 0) continue;

            const rate = parseFloat(
              (entry.hourlyRateSnapshot ?? project.hourlyRate ?? "0").toString()
            );

            const date = invoiceTimeEntryCalendarIsoDate(entry);
            unbilledEntries.push({
              id: entry.id,
              taskId: task.id,
              taskTitle: task.title,
              taskDescription: task.description ?? null,
              minutes,
              hourlyRate: rate,
              date,
              userName: entry.user.name ?? entry.user.email ?? "Unknown",
              entryDescription: entry.description ?? null,
            });
          }
        }

        if (unbilledEntries.length === 0) continue;

        // Group by task
        const byTask = new Map<string, { title: string; minutes: number; rate: number }>();
        for (const e of unbilledEntries) {
          const existing = byTask.get(e.taskId);
          if (existing) {
            existing.minutes += e.minutes;
          } else {
            byTask.set(e.taskId, { title: e.taskTitle, minutes: e.minutes, rate: e.hourlyRate });
          }
        }

        const taskMetaMap = new Map<string, InvoiceLineMetadata>();
        for (const [taskId, taskData] of byTask.entries()) {
          const taskEntry = unbilledEntries.find((e) => e.taskId === taskId);
          taskMetaMap.set(taskId, {
            taskTitle: taskData.title,
            taskDescription: taskEntry?.taskDescription ?? null,
            projectName: project.name,
            projectId: project.id,
            entries: mergeInvoiceEntriesSameCalendarDayAndUser(
              unbilledEntries
                .filter((e) => e.taskId === taskId)
                .map((e) => ({
                  date: e.date,
                  minutes: e.minutes,
                  userName: e.userName,
                  description: e.entryDescription,
                }))
            ),
          });
        }

        const vatRate = parseFloat(orgSettings?.vatRate?.toString() ?? "0");

        let lines: Array<{
          description: string;
          quantity: string;
          unitPrice: string;
          total: string;
          taskId: string;
          metadata: InvoiceLineMetadata | null;
        }>;

        if (project.billingMode === BillingMode.FIXED) {
          const fixedFee = parseFloat((project.fixedFee ?? "0").toString());
          lines = [
            {
              description: `${project.name} - Fixed fee`,
              quantity: "1",
              unitPrice: fixedFee.toFixed(2),
              total: fixedFee.toFixed(2),
              taskId: "",
              metadata: null,
            },
          ];
        } else {
          lines = Array.from(byTask.entries()).map(([taskId, { title, minutes, rate }]) => {
            const hours = minutes / 60;
            const total = hours * rate;
            return {
              description: `${title} (${hours.toFixed(2)}h @ ${project.currency} ${rate}/h)`,
              quantity: hours.toFixed(4),
              unitPrice: rate.toFixed(2),
              total: total.toFixed(2),
              taskId,
              metadata: taskMetaMap.get(taskId) ?? null,
            };
          });
        }

        const subtotal = lines.reduce((sum, l) => sum + parseFloat(l.total), 0);
        const vat = subtotal * vatRate;
        const total = subtotal + vat;

        let createdNumber: string | undefined;
        try {
          alloc: for (let attempt = 0; attempt < MAX_DOC_NUMBER_ATTEMPTS; attempt++) {
            const number = await getNextInvoiceNumber(project.organizationId);
            try {
              await prisma.$transaction(async (tx) => {
                const invoice = await tx.invoice.create({
                  data: {
                    organizationId: project.organizationId,
                    customerId: project.customerId,
                    number,
                    currency: project.currency,
                    subtotal: subtotal.toFixed(2),
                    vat: vat.toFixed(2),
                    total: total.toFixed(2),
                    issuedAt: today,
                    dueDate: new Date(today.getTime() + 30 * 24 * 60 * 60 * 1000),
                    status: orgSettings?.autoSendInvoice ? "SENT" : "DRAFT",
                    periodFrom: periodFrom ?? null,
                    periodTo: periodTo ?? null,
                    lines: {
                      create: lines.map((l, i) => ({
                        name: l.description,
                        description: null,
                        quantity: l.quantity,
                        unitPrice: l.unitPrice,
                        total: l.total,
                        sortOrder: i,
                        taskId: l.taskId || null,
                        metadata: (l.metadata ?? undefined) as Prisma.InputJsonValue | undefined,
                      })),
                    },
                  },
                  include: { lines: true },
                });

                for (const line of invoice.lines) {
                  if (!line.taskId) continue;
                  const entryIds = unbilledEntries
                    .filter((e) => e.taskId === line.taskId)
                    .map((e) => e.id);
                  if (entryIds.length > 0) {
                    await tx.timeEntry.updateMany({
                      where: { id: { in: entryIds } },
                      data: { billed: true, invoiceLineId: line.id },
                    });
                  }
                }

                if (project.billingMode === BillingMode.FIXED) {
                  await tx.timeEntry.updateMany({
                    where: { id: { in: unbilledEntries.map((e) => e.id) } },
                    data: { billed: true, invoiceLineId: invoice.lines[0]?.id },
                  });
                }
              });

              createdNumber = number;
              break alloc;
            } catch (err) {
              if (isInvoiceOrgNumberUniqueConflict(err)) continue;
              throw err;
            }
          }

          if (createdNumber) {
            console.log(
              `[AutoBilling] Created invoice ${createdNumber} for project ${project.id} (${project.name})`
            );
          }
        } catch (err) {
          console.error(`[AutoBilling] Failed for project ${project.id}:`, err);
        }
      }
    },
    { connection: createBullmqConnection() }
  );

  worker.on("completed", () => console.log("[AutoBilling] Run completed"));
  worker.on("failed", (job, err) => console.error("[AutoBilling] Job failed:", err));

  return worker;
}
