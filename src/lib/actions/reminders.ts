"use server";

import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { ReminderChannel, ReminderTargetType } from "@prisma/client";
import { revalidatePath } from "next/cache";
import { z } from "zod";

const createReminderSchema = z.object({
  targetType: z.nativeEnum(ReminderTargetType),
  targetId: z.string().min(1),
  title: z.string().min(2),
  description: z.string().optional(),
  notifyAt: z.string().min(1),
  channels: z.array(z.nativeEnum(ReminderChannel)).min(1),
  recipientIds: z.array(z.string().min(1)).min(1),
});

export type CreateReminderInput = z.infer<typeof createReminderSchema>;

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
      organizationId_userId: {
        organizationId: org.id,
        userId: session.user.id,
      },
    },
    select: { id: true },
  });
  return member ? org.id : null;
}

export async function createReminder(orgSlug: string, input: CreateReminderInput) {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = createReminderSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const notifyAt = new Date(parsed.data.notifyAt);
  if (Number.isNaN(notifyAt.getTime())) return { error: "Invalid notify date" };

  const sameOrgUsers = await prisma.user.findMany({
    where: {
      id: { in: parsed.data.recipientIds },
      OR: [
        { orgMemberships: { some: { organizationId: orgId } } },
        { contactProfile: { is: { organizationId: orgId } } },
      ],
    },
    select: { id: true },
  });

  if (sameOrgUsers.length !== parsed.data.recipientIds.length) {
    return { error: "One or more recipients are not in this organization" };
  }

  await prisma.reminder.create({
    data: {
      organizationId: orgId,
      targetType: parsed.data.targetType,
      targetId: parsed.data.targetId,
      title: parsed.data.title,
      description: parsed.data.description,
      notifyAt,
      channels: parsed.data.channels,
      recipientIds: parsed.data.recipientIds,
    },
  });

  revalidatePath(`/${orgSlug}/reminders`);
  if (parsed.data.targetType === "INVOICE") {
    revalidatePath(`/${orgSlug}/invoices/${parsed.data.targetId}`);
  }
  return { success: true };
}

export async function deleteReminder(orgSlug: string, reminderId: string) {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const existing = await prisma.reminder.findUnique({
    where: { id: reminderId, organizationId: orgId },
    select: { targetType: true, targetId: true },
  });
  if (!existing) return { error: "Reminder not found" };

  await prisma.reminder.delete({
    where: { id: reminderId, organizationId: orgId },
  });

  revalidatePath(`/${orgSlug}/reminders`);
  if (existing.targetType === "INVOICE" && existing.targetId) {
    revalidatePath(`/${orgSlug}/invoices/${existing.targetId}`);
  }
  return { success: true };
}

export async function markNotificationRead(notificationId: string) {
  const session = await auth();
  if (!session?.user) return { error: "Unauthorized" };

  const existing = await prisma.notification.findUnique({
    where: { id: notificationId },
    select: { userId: true },
  });
  if (!existing || existing.userId !== session.user.id) return { error: "Forbidden" };

  await prisma.notification.update({
    where: { id: notificationId },
    data: { readAt: new Date() },
  });

  return { success: true };
}

export async function markAllNotificationsRead(orgSlug: string) {
  const session = await auth();
  if (!session?.user) return { error: "Unauthorized" };

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) return { error: "Organization not found" };

  await prisma.notification.updateMany({
    where: {
      organizationId: org.id,
      userId: session.user.id,
      readAt: null,
    },
    data: { readAt: new Date() },
  });

  revalidatePath(`/${orgSlug}/notifications`);
  return { success: true };
}
