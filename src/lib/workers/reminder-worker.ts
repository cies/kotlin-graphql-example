import { Queue, Worker } from "bullmq";
import { prisma } from "@/lib/db/prisma";
import { sendMail } from "@/lib/email/mailer";
import { composeEmail } from "@/lib/email/compose";
import { sendTwilioSms } from "@/lib/sms/twilio";
import { appendInvoiceEmailOpenPixel } from "@/lib/invoice/email-open-pixel";
import { formatCurrency, normalizeNumberFormatStyle } from "@/lib/utils/format";

import { createBullmqConnection } from "./redis-connection";
import { resolveInvoiceNotificationEmails } from "@/lib/customers/notification-email-resolve";

export const reminderQueue = new Queue("reminders", { connection: createBullmqConnection() });
export const overdueInvoiceQueue = new Queue("overdue-invoices", {
  connection: createBullmqConnection(),
});

export async function scheduleReminderWorkers() {
  await reminderQueue.add(
    "run",
    {},
    {
      repeat: { pattern: "* * * * *" },
      jobId: "minute-reminders",
    }
  );

  await overdueInvoiceQueue.add(
    "run",
    {},
    {
      repeat: { pattern: "0 9 * * *" },
      jobId: "daily-overdue-invoices",
    }
  );
}

export function startReminderWorker() {
  const worker = new Worker(
    "reminders",
    async () => {
      const now = new Date();

      const dueReminders = await prisma.reminder.findMany({
        where: {
          sent: false,
          notifyAt: { lte: now },
        },
        include: {
          organization: { include: { settings: true } },
        },
      });

      for (const reminder of dueReminders) {
        const channels = reminder.channels;
        const recipientUsers = await prisma.user.findMany({
          where: { id: { in: reminder.recipientIds } },
          select: {
            id: true,
            email: true,
            name: true,
            contactProfile: { select: { customer: { select: { phone: true } } } },
          },
        });

        if (channels.includes("EMAIL")) {
          const recipients = recipientUsers
            .map((u) => u.email)
            .filter(Boolean) as string[];

          if (recipients.length > 0) {
            const targetLabel =
              reminder.targetType === "CUSTOMER"
                ? "Customer"
                : reminder.targetType === "PROJECT"
                ? "Project"
                : reminder.targetType === "TASK"
                ? "Task"
                : "Invoice";

            const { subject, html, attachments } = await composeEmail(
              reminder.organizationId,
              "reminder.generic",
              {
                "reminder.title": reminder.title,
                "reminder.description": reminder.description ?? "",
                "reminder.notifyAt": reminder.notifyAt.toLocaleString("en-GB"),
                "target.label": targetLabel,
                "org.name":
                  reminder.organization.settings?.companyName ??
                  reminder.organization.name,
              }
            );

            await sendMail({
              orgId: reminder.organizationId,
              to: recipients,
              subject: subject || reminder.title,
              html: html || `<p>${reminder.description ?? reminder.title}</p>`,
              attachments,
            });
          }
        }

        if (channels.includes("SMS")) {
          for (const user of recipientUsers) {
            const phone = user.contactProfile?.customer.phone;
            if (!phone) continue;
            await sendTwilioSms({
              organizationId: reminder.organizationId,
              to: phone,
              body: `${reminder.title}${reminder.description ? `: ${reminder.description}` : ""}`,
            });
          }
        }

        if (channels.includes("INAPP")) {
          await prisma.notification.createMany({
            data: recipientUsers.map((user) => ({
              organizationId: reminder.organizationId,
              userId: user.id,
              title: reminder.title,
              body: reminder.description,
            })),
            skipDuplicates: false,
          });
        }

        await prisma.reminder.update({
          where: { id: reminder.id },
          data: { sent: true, sentAt: now },
        });
      }
    },
    { connection: createBullmqConnection() }
  );

  worker.on("failed", (job, err) => console.error("[Reminder] Job failed:", err));

  return worker;
}

export function startOverdueInvoiceWorker() {
  const worker = new Worker(
    "overdue-invoices",
    async () => {
      const today = new Date();
      today.setHours(0, 0, 0, 0);

      const orgs = await prisma.organization.findMany({
        select: { id: true, name: true, settings: true },
      });

      for (const org of orgs) {
        const reminderDays = (org.settings?.overdueReminderDays as number[]) ?? [3, 7, 14];

        const overdueInvoices = await prisma.invoice.findMany({
          where: {
            organizationId: org.id,
            status: { in: ["SENT", "PARTIAL", "OVERDUE"] },
            dueDate: { lt: today },
          },
          include: {
            customer: { include: { notificationEmails: true } },
            reminderLogs: true,
          },
        });

        for (const invoice of overdueInvoices) {
          const toList = resolveInvoiceNotificationEmails(invoice.customer);
          if (!invoice.dueDate || toList.length === 0) continue;

          const daysOverdue = Math.floor(
            (today.getTime() - invoice.dueDate.getTime()) / (24 * 60 * 60 * 1000)
          );
          const numberFormatStyle = normalizeNumberFormatStyle(
            (org.settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
          );

          for (const day of reminderDays) {
            if (daysOverdue < day) continue;

            const alreadySent = invoice.reminderLogs.some((log) => log.daysPast === day);
            if (alreadySent) continue;

            const customerName =
              invoice.customer.companyName ??
              [invoice.customer.firstName, invoice.customer.lastName].filter(Boolean).join(" ") ??
              invoice.customer.email ??
              toList[0] ??
              "Customer";

            const { subject, html, attachments } = await composeEmail(
              org.id,
              "invoice.overdue",
              {
                "invoice.number": invoice.number,
                "invoice.currency": invoice.currency,
                "invoice.total": invoice.total.toString(),
                "invoice.totalFormatted": formatCurrency(invoice.total.toString(), invoice.currency, numberFormatStyle),
                "invoice.dueDate": invoice.dueDate.toLocaleDateString("en-GB"),
                "customer.name": customerName,
                "org.name": org.settings?.companyName ?? org.name,
                daysOverdue: String(daysOverdue),
              }
            );

            const trackOpens = org.settings?.trackInvoiceEmailOpens ?? true;
            const toEmailLog = toList.join(", ");
            const emailSend = await prisma.invoiceEmailSend.create({
              data: {
                organizationId: org.id,
                invoiceId: invoice.id,
                kind: "OVERDUE_REMINDER",
                toEmail: toEmailLog,
                subject,
              },
            });

            const htmlToSend = trackOpens
              ? appendInvoiceEmailOpenPixel(html, emailSend.id)
              : html;

            const result = await sendMail({
              orgId: org.id,
              to: toList,
              subject,
              html: htmlToSend,
              attachments,
            });

            if (!result.ok) {
              await prisma.invoiceEmailSend.delete({ where: { id: emailSend.id } }).catch(() => {});
            }

            if (result.ok) {
              await prisma.$transaction([
                prisma.invoiceReminderLog.create({
                  data: { invoiceId: invoice.id, daysPast: day },
                }),
                prisma.invoice.update({
                  where: { id: invoice.id },
                  data: { status: "OVERDUE" },
                }),
              ]);

              console.log(
                `[OverdueInvoice] Sent day-${day} reminder for ${invoice.number} to ${toEmailLog}`
              );
            }

            break; // Send only the most relevant reminder per run
          }
        }
      }
    },
    { connection: createBullmqConnection() }
  );

  worker.on("completed", () => console.log("[OverdueInvoice] Run completed"));
  worker.on("failed", (job, err) => console.error("[OverdueInvoice] Job failed:", err));

  return worker;
}
