import { NextRequest } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { csvResponse, rowsToCsv } from "@/lib/filters/csv";
import { logAudit } from "@/lib/audit/log";

interface Ctx {
  params: Promise<{ orgSlug: string; projectId: string }>;
}

function slugFilenamePart(name: string): string {
  return name.replace(/[^\w\-]+/g, "_").slice(0, 60) || "project";
}

export async function GET(_req: NextRequest, { params }: Ctx) {
  const { orgSlug, projectId } = await params;
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") {
    return new Response("Unauthorized", { status: 401 });
  }

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) return new Response("Not found", { status: 404 });

  const member = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: { organizationId: org.id, userId: session.user.id },
    },
  });
  if (!member) return new Response("Forbidden", { status: 403 });

  const project = await prisma.project.findUnique({
    where: { id: projectId, organizationId: org.id },
    include: {
      customer: {
        select: { companyName: true, firstName: true, lastName: true, type: true },
      },
      tasks: {
        orderBy: [{ createdAt: "asc" }],
      },
      _count: { select: { tasks: true } },
    },
  });
  if (!project) return new Response("Not found", { status: 404 });

  const customerLabel =
    project.customer.type === "B2B"
      ? project.customer.companyName ?? ""
      : `${project.customer.firstName ?? ""} ${project.customer.lastName ?? ""}`.trim();

  const projectRow = {
    name: project.name,
    customer: customerLabel,
    billingMode: project.billingMode,
    hourlyRate: project.hourlyRate ? project.hourlyRate.toString() : "",
    fixedFee: project.fixedFee ? project.fixedFee.toString() : "",
    currency: project.currency,
    autoInvoice: project.autoInvoice ? "yes" : "no",
    autoInvoiceCycle: project.autoInvoiceCycle,
    statusEmailCycle: project.statusEmailCycle,
    status: project.status,
    taskCount: project._count.tasks,
    startDate: project.startDate ? project.startDate.toISOString() : "",
    endDate: project.endDate ? project.endDate.toISOString() : "",
    createdAt: project.createdAt.toISOString(),
  };

  const projectCsv = rowsToCsv([projectRow], [
    { key: "name", label: "Name" },
    { key: "customer", label: "Customer" },
    { key: "billingMode", label: "Billing Mode" },
    { key: "hourlyRate", label: "Hourly Rate" },
    { key: "fixedFee", label: "Fixed Fee" },
    { key: "currency", label: "Currency" },
    { key: "autoInvoice", label: "Auto Invoice" },
    { key: "autoInvoiceCycle", label: "Auto Invoice Cycle" },
    { key: "statusEmailCycle", label: "Status Email Cycle" },
    { key: "status", label: "Status" },
    { key: "taskCount", label: "Tasks" },
    { key: "startDate", label: "Start Date" },
    { key: "endDate", label: "End Date" },
    { key: "createdAt", label: "Created At" },
  ]);

  const taskRows = project.tasks.map((t) => ({
    title: t.title,
    status: t.status,
    priority: t.priority,
    estimatedHours: t.estimatedHours != null ? t.estimatedHours.toString() : "",
    hourlyRate: t.hourlyRate != null ? t.hourlyRate.toString() : "",
    dueDate: t.dueDate ? t.dueDate.toISOString() : "",
    createdAt: t.createdAt.toISOString(),
  }));

  const taskColumns = [
    { key: "title" as const, label: "Task Title" },
    { key: "status" as const, label: "Task Status" },
    { key: "priority" as const, label: "Priority" },
    { key: "estimatedHours" as const, label: "Estimated Hours" },
    { key: "hourlyRate" as const, label: "Task Hourly Rate" },
    { key: "dueDate" as const, label: "Due Date" },
    { key: "createdAt" as const, label: "Created At" },
  ];

  const tasksCsv =
    taskRows.length > 0
      ? rowsToCsv(taskRows, taskColumns)
      : `${taskColumns.map((c) => c.label).join(",")}\r\n`;

  const csv = `${projectCsv}\r\n\r\nTasks\r\n${tasksCsv}`;
  const rowCount = 1 + taskRows.length;

  await logAudit({
    organizationId: org.id,
    action: "EXPORT",
    entityType: "PROJECT",
    entityId: projectId,
    metadata: { rowCount, projectId },
  });

  const safeName = slugFilenamePart(project.name);
  const date = new Date().toISOString().slice(0, 10);
  return csvResponse(`project-${safeName}-${date}.csv`, csv);
}
