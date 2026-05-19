"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { BillingMode, AutoInvoiceCycle, StatusEmailCycle, Currency } from "@prisma/client";
import { logAudit } from "@/lib/audit/log";
import { PROJECT_STATUS_VALUES, type ProjectStatusValue } from "@/lib/projects/project-status";

const projectStatusSchema = z.enum(PROJECT_STATUS_VALUES);

async function getStaffOrgContext(
  orgSlug: string
): Promise<{ orgId: string; userId: string; memberId: string } | null> {
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
    select: { id: true },
  });

  return member ? { orgId: org.id, userId: session.user.id, memberId: member.id } : null;
}

async function getOrgId(orgSlug: string): Promise<string | null> {
  const ctx = await getStaffOrgContext(orgSlug);
  return ctx?.orgId ?? null;
}

const projectSchema = z.object({
  customerId: z.string().min(1),
  name: z.string().min(1),
  description: z.string().optional(),
  billingMode: z.nativeEnum(BillingMode).default("HOURLY"),
  hourlyRate: z.string().optional(),
  fixedFee: z.string().optional(),
  currency: z.nativeEnum(Currency).default("EUR"),
  autoInvoice: z.boolean().default(false),
  autoInvoiceDay: z.number().int().min(1).max(31).optional(),
  autoInvoiceCycle: z.nativeEnum(AutoInvoiceCycle).default("MONTHLY"),
  semiMonthlyPeriodSplitDay: z.number().int().min(1).max(28).optional().nullable(),
  semiMonthlyEmitDay1: z.number().int().min(1).max(31).optional().nullable(),
  semiMonthlyEmitDay2: z.number().int().min(1).max(31).optional().nullable(),
  statusEmailCycle: z.nativeEnum(StatusEmailCycle).default("NONE"),
  startDate: z.string().optional(),
  endDate: z.string().optional(),
});

export type ProjectInput = z.infer<typeof projectSchema>;

export async function createProject(orgSlug: string, input: ProjectInput) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = projectSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const { hourlyRate, fixedFee, startDate, endDate, ...rest } = parsed.data;

  const project = await prisma.project.create({
    data: {
      ...rest,
      organizationId: orgId,
      hourlyRate: hourlyRate ? hourlyRate : null,
      fixedFee: fixedFee ? fixedFee : null,
      startDate: startDate ? new Date(startDate) : null,
      endDate: endDate ? new Date(endDate) : null,
    },
  });

  revalidatePath(`/${orgSlug}/projects`);
  return { success: true, projectId: project.id };
}

export async function updateProject(
  orgSlug: string,
  projectId: string,
  input: ProjectInput
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = projectSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const { hourlyRate, fixedFee, startDate, endDate, ...rest } = parsed.data;

  await prisma.project.update({
    where: { id: projectId, organizationId: orgId },
    data: {
      ...rest,
      hourlyRate: hourlyRate ? hourlyRate : null,
      fixedFee: fixedFee ? fixedFee : null,
      startDate: startDate ? new Date(startDate) : null,
      endDate: endDate ? new Date(endDate) : null,
    },
  });

  revalidatePath(`/${orgSlug}/projects`);
  revalidatePath(`/${orgSlug}/projects/${projectId}`);
  return { success: true };
}

export async function deleteProject(orgSlug: string, projectId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  await logAudit({
    organizationId: orgId,
    action: "DELETE",
    entityType: "PROJECT",
    entityId: projectId,
  });

  await prisma.project.delete({ where: { id: projectId, organizationId: orgId } });

  revalidatePath(`/${orgSlug}/projects`);
  return { success: true };
}

export async function togglePinProject(orgSlug: string, projectId: string) {
  const ctx = await getStaffOrgContext(orgSlug);
  if (!ctx) return { error: "Unauthorized" };

  const project = await prisma.project.findUnique({
    where: { id: projectId, organizationId: ctx.orgId },
    select: { id: true },
  });
  if (!project) return { error: "Not found" };

  const member = await prisma.organizationMember.findUnique({
    where: { id: ctx.memberId },
    select: { pinnedProjectIds: true },
  });
  if (!member) return { error: "Unauthorized" };

  const pins = member.pinnedProjectIds;
  const pinned = pins.includes(projectId);
  const next = pinned ? pins.filter((id) => id !== projectId) : [...pins, projectId];

  await prisma.organizationMember.update({
    where: { id: ctx.memberId },
    data: { pinnedProjectIds: next },
  });

  revalidatePath(`/${orgSlug}/projects`);
  revalidatePath(`/${orgSlug}/projects/${projectId}`);
  return { success: true, pinned: !pinned };
}

export async function setProjectStatus(
  orgSlug: string,
  projectId: string,
  status: ProjectStatusValue
) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = projectStatusSchema.safeParse(status);
  if (!parsed.success) return { error: "Invalid status" };

  const updated = await prisma.project.updateMany({
    where: { id: projectId, organizationId: orgId },
    data: { status: parsed.data },
  });
  if (updated.count === 0) return { error: "Not found" };

  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "PROJECT",
    entityId: projectId,
    metadata: { status: parsed.data },
  });

  revalidatePath(`/${orgSlug}/projects`);
  revalidatePath(`/${orgSlug}/projects/${projectId}`);
  return { success: true };
}

export async function duplicateProject(orgSlug: string, projectId: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const src = await prisma.project.findUnique({
    where: { id: projectId, organizationId: orgId },
    include: {
      tasks: {
        orderBy: { createdAt: "asc" },
      },
    },
  });
  if (!src) return { error: "Not found" };

  const newProject = await prisma.project.create({
    data: {
      organizationId: src.organizationId,
      customerId: src.customerId,
      name: `${src.name} (Copy)`,
      description: src.description,
      billingMode: src.billingMode,
      hourlyRate: src.hourlyRate,
      fixedFee: src.fixedFee,
      currency: src.currency,
      autoInvoice: src.autoInvoice,
      autoInvoiceDay: src.autoInvoiceDay,
      autoInvoiceCycle: src.autoInvoiceCycle,
      semiMonthlyPeriodSplitDay: src.semiMonthlyPeriodSplitDay,
      semiMonthlyEmitDay1: src.semiMonthlyEmitDay1,
      semiMonthlyEmitDay2: src.semiMonthlyEmitDay2,
      statusEmailCycle: src.statusEmailCycle,
      startDate: src.startDate,
      endDate: src.endDate,
      status: src.status,
      tasks: {
        create: src.tasks.map((t) => ({
          organizationId: orgId,
          title: t.title,
          description: t.description,
          status: "TODO" as const,
          assigneeIds: [],
          estimatedHours: t.estimatedHours,
          hourlyRate: t.hourlyRate,
          dueDate: null,
          priority: t.priority,
        })),
      },
    },
  });

  await logAudit({
    organizationId: orgId,
    action: "CREATE",
    entityType: "PROJECT",
    entityId: newProject.id,
    metadata: { sourceProjectId: projectId },
  });

  revalidatePath(`/${orgSlug}/projects`);
  revalidatePath(`/${orgSlug}/projects/${newProject.id}`);
  return { success: true, projectId: newProject.id };
}
