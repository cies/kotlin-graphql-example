"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { applyTimeRounding } from "@/lib/utils/time-rounding";

async function getSession() {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") return null;
  return session;
}

async function getOrgId(orgSlug: string, userId: string): Promise<string | null> {
  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) return null;

  const member = await prisma.organizationMember.findUnique({
    where: { organizationId_userId: { organizationId: org.id, userId } },
  });

  return member ? org.id : null;
}

async function getTimeRounding(orgId: string): Promise<string> {
  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: { timeRounding: true },
  });
  return settings?.timeRounding ?? "NONE";
}

/** Compute rounded endedAt for a timer entry given rounding mode. */
function computeRoundedEndedAt(startedAt: Date, now: Date, roundingMode: string): Date {
  if (roundingMode === "NONE") return now;
  const actualMinutes = (now.getTime() - startedAt.getTime()) / 60000;
  const roundedMinutes = applyTimeRounding(actualMinutes, roundingMode);
  return new Date(startedAt.getTime() + roundedMinutes * 60000);
}

/** Strict YYYY-MM-DD → UTC midnight date for @db.Date */
function parseLoggedDateString(value: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
  if (!m) return null;
  const y = Number(m[1]);
  const mo = Number(m[2]) - 1;
  const d = Number(m[3]);
  const dt = new Date(Date.UTC(y, mo, d));
  if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== mo || dt.getUTCDate() !== d) return null;
  return dt;
}

function utcTodayEnd(): Date {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), 23, 59, 59, 999));
}

const manualEntrySchema = z.object({
  taskId: z.string().min(1),
  manualMinutes: z.number().int().positive(),
  /** ISO date string YYYY-MM-DD (calendar day work was performed, UTC). */
  loggedDate: z.string().min(1),
  description: z.string().optional(),
  hourlyRateSnapshot: z.string().optional(),
});

export type ManualEntryInput = z.infer<typeof manualEntrySchema>;

export async function addManualTimeEntry(
  orgSlug: string,
  input: ManualEntryInput
) {
  const session = await getSession();
  if (!session) return { error: "Unauthorized" };

  const orgId = await getOrgId(orgSlug, session.user.id);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = manualEntrySchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const loggedDate = parseLoggedDateString(parsed.data.loggedDate);
  if (!loggedDate) return { error: "Invalid date." };
  if (loggedDate.getTime() > utcTodayEnd().getTime()) {
    return { error: "Date cannot be in the future." };
  }

  const roundingMode = await getTimeRounding(orgId);
  const manualMinutes = applyTimeRounding(parsed.data.manualMinutes, roundingMode);

  const task = await prisma.task.findFirst({
    where: { id: parsed.data.taskId, organizationId: orgId },
    select: { projectId: true },
  });
  if (!task) return { error: "Task not found." };

  await prisma.timeEntry.create({
    data: {
      organizationId: orgId,
      taskId: parsed.data.taskId,
      userId: session.user.id,
      manualMinutes,
      loggedDate,
      workSortAt: loggedDate,
      description: parsed.data.description,
      hourlyRateSnapshot: parsed.data.hourlyRateSnapshot || null,
    },
  });

  revalidatePath(`/${orgSlug}/time`);
  revalidatePath(`/${orgSlug}/projects/${task.projectId}/tasks/${parsed.data.taskId}`);
  return { success: true };
}

export async function startTimer(orgSlug: string, taskId: string) {
  const session = await getSession();
  if (!session) return { error: "Unauthorized" };

  const orgId = await getOrgId(orgSlug, session.user.id);
  if (!orgId) return { error: "Unauthorized" };

  const userId = session.user.id;

  await prisma.$transaction(async (tx) => {
    // Serialize timer start/stop for this org+user (pairs with partial unique index on DB).
    await tx.$executeRaw`
      SELECT pg_advisory_xact_lock(
        hashtext(${orgId}::text),
        hashtext(${userId}::text)
      )
    `;

    const settings = await tx.orgSettings.findUnique({
      where: { organizationId: orgId },
      select: { timeRounding: true },
    });
    const roundingMode = settings?.timeRounding ?? "NONE";

    const runningEntry = await tx.timeEntry.findFirst({
      where: {
        organizationId: orgId,
        userId,
        startedAt: { not: null },
        endedAt: null,
        manualMinutes: null,
      },
    });

    const now = new Date();
    if (runningEntry) {
      const endedAt = runningEntry.startedAt
        ? computeRoundedEndedAt(runningEntry.startedAt, now, roundingMode)
        : now;
      await tx.timeEntry.update({
        where: { id: runningEntry.id },
        data: { endedAt },
      });
    }

    await tx.timeEntry.create({
      data: {
        organizationId: orgId,
        taskId,
        userId,
        startedAt: now,
        workSortAt: now,
      },
    });
  });

  revalidatePath(`/${orgSlug}/time`);
  return { success: true };
}

export async function stopTimer(orgSlug: string, entryId: string) {
  const session = await getSession();
  if (!session) return { error: "Unauthorized" };

  const orgId = await getOrgId(orgSlug, session.user.id);
  if (!orgId) return { error: "Unauthorized" };

  const userId = session.user.id;

  const stopped = await prisma.$transaction(async (tx) => {
    await tx.$executeRaw`
      SELECT pg_advisory_xact_lock(
        hashtext(${orgId}::text),
        hashtext(${userId}::text)
      )
    `;

    const entry = await tx.timeEntry.findUnique({
      where: { id: entryId, userId, organizationId: orgId },
      select: { startedAt: true, endedAt: true },
    });
    if (!entry) return false as const;
    if (entry.endedAt) return "already" as const;

    const settings = await tx.orgSettings.findUnique({
      where: { organizationId: orgId },
      select: { timeRounding: true },
    });
    const roundingMode = settings?.timeRounding ?? "NONE";
    const now = new Date();
    const endedAt = entry.startedAt
      ? computeRoundedEndedAt(entry.startedAt, now, roundingMode)
      : now;

    const result = await tx.timeEntry.updateMany({
      where: {
        id: entryId,
        userId,
        organizationId: orgId,
        endedAt: null,
      },
      data: { endedAt },
    });
    return result.count === 1 ? true : ("already" as const);
  });

  if (stopped === false) return { error: "Entry not found" };

  revalidatePath(`/${orgSlug}/time`);
  return { success: true };
}

export async function deleteTimeEntry(orgSlug: string, entryId: string) {
  const session = await getSession();
  if (!session) return { error: "Unauthorized" };

  const orgId = await getOrgId(orgSlug, session.user.id);
  if (!orgId) return { error: "Unauthorized" };

  await prisma.timeEntry.delete({
    where: { id: entryId, organizationId: orgId, billed: false },
  });

  revalidatePath(`/${orgSlug}/time`);
  return { success: true };
}

export async function getTaskHourSummary(orgId: string, taskId: string) {
  const entries = await prisma.timeEntry.findMany({
    where: {
      taskId,
      organizationId: orgId,
      // Manual entries have no timer window; running timers omit both branches.
      OR: [{ manualMinutes: { not: null } }, { endedAt: { not: null } }],
    },
    select: {
      manualMinutes: true,
      startedAt: true,
      endedAt: true,
      billed: true,
      invoiceLine: {
        select: {
          invoice: {
            select: { status: true, amountPaid: true, total: true },
          },
        },
      },
    },
  });

  let loggedMinutes = 0;
  let billedMinutes = 0;
  let paidMinutes = 0;

  for (const e of entries) {
    const minutes =
      e.manualMinutes ??
      (e.startedAt && e.endedAt
        ? Math.round((e.endedAt.getTime() - e.startedAt.getTime()) / 60000)
        : 0);

    loggedMinutes += minutes;

    if (e.billed) {
      billedMinutes += minutes;
      const invoice = e.invoiceLine?.invoice;
      if (invoice && (invoice.status === "PAID" || invoice.status === "PARTIAL")) {
        paidMinutes += minutes;
      }
    }
  }

  return {
    loggedMinutes,
    billedMinutes,
    paidMinutes,
    unbilledMinutes: loggedMinutes - billedMinutes,
    unpaidBilledMinutes: billedMinutes - paidMinutes,
  };
}
