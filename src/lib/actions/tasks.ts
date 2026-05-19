"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { TaskStatus } from "@prisma/client";
import fs from "fs/promises";
import path from "path";

// ─── Activity helper ──────────────────────────────────────────────────────────

async function recordActivity(
  taskId: string,
  orgId: string,
  type: string,
  metadata?: Record<string, unknown>
) {
  try {
    const session = await auth();
    const userId = session?.user?.id ?? null;
    const actorName =
      session?.user?.name ?? session?.user?.email ?? null;
    await prisma.taskActivity.create({
      data: { taskId, organizationId: orgId, userId, actorName, type, metadata: (metadata ?? null) as never },
    });
  } catch {
    // never block the main action
  }
}

async function getOrgId(orgSlug: string): Promise<string | null> {
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
  });

  return member ? org.id : null;
}

const taskSchema = z.object({
  title: z.string().min(1),
  description: z.string().optional(),
  status: z.nativeEnum(TaskStatus).default("TODO"),
  assigneeIds: z.array(z.string()).default([]),
  estimatedHours: z.string().optional(),
  hourlyRate: z.string().optional(),
  dueDate: z.string().optional(),
  priority: z.number().int().default(0),
});

export type TaskInput = z.infer<typeof taskSchema>;

export async function createTask(
  orgSlug: string,
  projectId: string,
  input: TaskInput
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = taskSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const { estimatedHours, hourlyRate, dueDate, ...rest } = parsed.data;

  const task = await prisma.task.create({
    data: {
      ...rest,
      organizationId: orgId,
      projectId,
      estimatedHours: estimatedHours ? estimatedHours : null,
      hourlyRate: hourlyRate ? hourlyRate : null,
      dueDate: dueDate ? new Date(dueDate) : null,
    },
  });

  await recordActivity(task.id, orgId, "CREATED");

  revalidatePath(`/${orgSlug}/projects/${projectId}`);
  return { success: true, taskId: task.id };
}

export async function updateTask(
  orgSlug: string,
  projectId: string,
  taskId: string,
  input: Partial<TaskInput>
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const current = await prisma.task.findUnique({
    where: { id: taskId, organizationId: orgId },
    select: { title: true, status: true, assigneeIds: true, hourlyRate: true },
  });
  if (!current) return { error: "Task not found" };

  const { estimatedHours, hourlyRate, dueDate, ...rest } = input;

  await prisma.task.update({
    where: { id: taskId, organizationId: orgId },
    data: {
      ...rest,
      ...(estimatedHours !== undefined && { estimatedHours: estimatedHours || null }),
      ...(hourlyRate !== undefined && { hourlyRate: hourlyRate || null }),
      ...(dueDate !== undefined && { dueDate: dueDate ? new Date(dueDate) : null }),
    },
  });

  // Record activities for meaningful changes
  if (input.status !== undefined && input.status !== current.status) {
    await recordActivity(taskId, orgId, "STATUS_CHANGED", {
      from: current.status,
      to: input.status,
    });
  }
  if (input.title !== undefined && input.title !== current.title) {
    await recordActivity(taskId, orgId, "TITLE_CHANGED", {
      from: current.title,
      to: input.title,
    });
  }
  if (input.assigneeIds !== undefined) {
    const prev = [...current.assigneeIds].sort();
    const next = [...input.assigneeIds].sort();
    if (JSON.stringify(prev) !== JSON.stringify(next)) {
      const added = next.filter((id) => !prev.includes(id));
      const removed = prev.filter((id) => !next.includes(id));
      await recordActivity(taskId, orgId, "ASSIGNEES_CHANGED", { added, removed });
    }
  }
  if (hourlyRate !== undefined) {
    const prevRate = current.hourlyRate ? parseFloat(current.hourlyRate.toString()) : null;
    const nextRate = hourlyRate ? parseFloat(hourlyRate) : null;
    if (prevRate !== nextRate) {
      await recordActivity(taskId, orgId, "RATE_CHANGED", {
        from: prevRate,
        to: nextRate,
      });
    }
  }

  revalidatePath(`/${orgSlug}/projects/${projectId}`);
  revalidatePath(`/${orgSlug}/projects/${projectId}/tasks/${taskId}`);
  return { success: true };
}

export async function deleteTask(
  orgSlug: string,
  projectId: string,
  taskId: string
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  await prisma.task.delete({ where: { id: taskId, organizationId: orgId } });

  revalidatePath(`/${orgSlug}/projects/${projectId}`);
  return { success: true };
}

// ─── Description ──────────────────────────────────────────────────────────────

export async function editTaskDescription(
  orgSlug: string,
  taskId: string,
  description: string
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const task = await prisma.task.findUnique({
    where: { id: taskId, organizationId: orgId },
    select: { projectId: true },
  });
  if (!task) return { error: "Task not found" };

  await prisma.task.update({
    where: { id: taskId, organizationId: orgId },
    data: { description: description.trim() || null },
  });

  await recordActivity(taskId, orgId, "DESCRIPTION_CHANGED");

  revalidatePath(`/${orgSlug}/projects/${task.projectId}/tasks/${taskId}`);
  return { success: true };
}

// ─── Comments ─────────────────────────────────────────────────────────────────

export async function createTaskComment(
  orgSlug: string,
  taskId: string,
  body: string
) {
  const session = await auth();
  if (!session?.user) return { error: "Unauthorized" };

  let orgId: string | null = null;

  if (session.user.userType === "STAFF") {
    orgId = await getOrgId(orgSlug);
  } else if (session.user.userType === "CUSTOMER_CONTACT") {
    const org = await prisma.organization.findUnique({
      where: { slug: orgSlug },
      select: { id: true },
    });
    if (!org) return { error: "Unauthorized" };
    const contact = await prisma.customerContact.findFirst({
      where: { organizationId: org.id, userId: session.user.id, canSeeTasks: true },
    });
    if (!contact) return { error: "Unauthorized" };
    orgId = org.id;
  }

  if (!orgId) return { error: "Unauthorized" };

  if (!body.trim()) return { error: "Comment cannot be empty" };

  const task = await prisma.task.findUnique({
    where: { id: taskId, organizationId: orgId },
    select: { projectId: true },
  });
  if (!task) return { error: "Task not found" };

  await prisma.taskComment.create({
    data: {
      organizationId: orgId,
      taskId,
      authorId: session.user.id,
      body: body.trim(),
    },
  });

  revalidatePath(`/${orgSlug}/projects/${task.projectId}/tasks/${taskId}`);
  if (session.user.userType === "CUSTOMER_CONTACT") {
    revalidatePath(`/portal/${orgSlug}/tasks/${taskId}`);
  }
  return { success: true };
}

export async function deleteTaskComment(
  orgSlug: string,
  commentId: string
) {
  const session = await auth();
  if (!session?.user) return { error: "Unauthorized" };

  const comment = await prisma.taskComment.findUnique({
    where: { id: commentId },
    include: { task: { select: { projectId: true } } },
  });
  if (!comment) return { error: "Comment not found" };

  if (session.user.userType === "STAFF") {
    const orgId = await getOrgId(orgSlug);
    if (orgId !== comment.organizationId) return { error: "Unauthorized" };
  } else {
    // Customer contacts can only delete their own comments
    if (comment.authorId !== session.user.id) return { error: "Unauthorized" };
  }

  await prisma.taskComment.delete({ where: { id: commentId } });

  revalidatePath(`/${orgSlug}/projects/${comment.task.projectId}/tasks/${comment.taskId}`);
  revalidatePath(`/portal/${orgSlug}/tasks/${comment.taskId}`);
  return { success: true };
}

// ─── Attachments ──────────────────────────────────────────────────────────────

export async function deleteTaskAttachment(
  orgSlug: string,
  attachmentId: string
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const attachment = await prisma.taskAttachment.findUnique({
    where: { id: attachmentId, organizationId: orgId },
    include: { task: { select: { projectId: true } } },
  });
  if (!attachment) return { error: "Attachment not found" };

  // Delete file from disk
  try {
    await fs.unlink(path.join(process.cwd(), attachment.storagePath));
  } catch {
    // File already gone - still clean up DB row
  }

  await prisma.taskAttachment.delete({ where: { id: attachmentId } });

  revalidatePath(`/${orgSlug}/projects/${attachment.task.projectId}/tasks/${attachment.taskId}`);
  return { success: true };
}
