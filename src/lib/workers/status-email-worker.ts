import { Queue, Worker } from "bullmq";
import { prisma } from "@/lib/db/prisma";
import { sendMail } from "@/lib/email/mailer";
import { composeEmail } from "@/lib/email/compose";
import { StatusEmailCycle } from "@prisma/client";
import { createBullmqConnection } from "./redis-connection";
import { resolveDigestNotificationEmails } from "@/lib/customers/notification-email-resolve";

export const statusEmailQueue = new Queue("status-emails", {
  connection: createBullmqConnection(),
});

export async function scheduleStatusEmailWorker() {
  await statusEmailQueue.add(
    "run",
    {},
    {
      repeat: { pattern: "0 8 * * 1" }, // Every Monday 08:00
      jobId: "weekly-status-emails",
    }
  );
}

function shouldSendThisWeek(cycle: StatusEmailCycle, weekNumber: number): boolean {
  switch (cycle) {
    case "WEEKLY":
      return true;
    case "BIWEEKLY":
      return weekNumber % 2 === 0;
    case "MONTHLY":
      return weekNumber % 4 === 0;
    default:
      return false;
  }
}

export function startStatusEmailWorker() {
  const worker = new Worker(
    "status-emails",
    async () => {
      const now = new Date();
      const startOfYear = new Date(now.getFullYear(), 0, 1);
      const weekNumber = Math.floor(
        (now.getTime() - startOfYear.getTime()) / (7 * 24 * 60 * 60 * 1000)
      );

      const periodStart = new Date(now);
      periodStart.setDate(periodStart.getDate() - 7);

      const projects = await prisma.project.findMany({
        where: {
          statusEmailCycle: { not: "NONE" },
          status: "ACTIVE",
        },
        include: {
          organization: { include: { settings: true } },
          customer: {
            include: {
              notificationEmails: true,
              contacts: {
                where: { isPrimary: true },
                include: { user: { select: { email: true, name: true } } },
              },
            },
          },
          tasks: {
            include: {
              timeEntries: {
                where: {
                  endedAt: { gte: periodStart, lte: now },
                  OR: [{ manualMinutes: { not: null } }, { startedAt: { not: null } }],
                },
              },
            },
          },
        },
      });

      for (const project of projects) {
        if (!shouldSendThisWeek(project.statusEmailCycle, weekNumber)) continue;

        const digestTo = resolveDigestNotificationEmails(project.customer);
        if (digestTo.length === 0) continue;

        // Build digest content
        const taskSummaries: string[] = [];
        let totalMinutes = 0;

        for (const task of project.tasks) {
          let taskMinutes = 0;
          for (const entry of task.timeEntries) {
            taskMinutes +=
              entry.manualMinutes ??
              (entry.startedAt && entry.endedAt
                ? Math.round((entry.endedAt.getTime() - entry.startedAt.getTime()) / 60000)
                : 0);
          }

          if (taskMinutes > 0) {
            const hours = (taskMinutes / 60).toFixed(1);
            taskSummaries.push(`• ${task.title}: ${hours}h`);
            totalMinutes += taskMinutes;
          }
        }

        if (taskSummaries.length === 0) continue;

        const totalHours = (totalMinutes / 60).toFixed(1);
        const digestContent = [
          `Period: ${periodStart.toLocaleDateString("en-GB")} – ${now.toLocaleDateString("en-GB")}`,
          `Total hours logged: ${totalHours}h`,
          "",
          "By task:",
          ...taskSummaries,
        ].join("\n");

        const primaryContact = project.customer.contacts[0];
        const customerName =
          project.customer.companyName ??
          [project.customer.firstName, project.customer.lastName].filter(Boolean).join(" ") ??
          primaryContact?.user?.email ??
          digestTo[0];

        const { subject, html, attachments } = await composeEmail(
          project.organizationId,
          "task.weekly_digest",
          {
            "project.name": project.name,
            "customer.name": customerName,
            "digest.content": digestContent.replace(/\n/g, "<br>"),
            "org.name":
              project.organization.settings?.companyName ?? project.organization.name,
          }
        );

        await sendMail({
          orgId: project.organizationId,
          to: digestTo,
          subject,
          html,
          attachments,
        });

        console.log(
          `[StatusEmail] Sent digest for project ${project.name} to ${digestTo.join(", ")}`
        );
      }
    },
    { connection: createBullmqConnection() }
  );

  worker.on("completed", () => console.log("[StatusEmail] Run completed"));
  worker.on("failed", (job, err) => console.error("[StatusEmail] Job failed:", err));

  return worker;
}
