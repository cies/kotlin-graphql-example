import { NextRequest } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { csvResponse, rowsToCsv } from "@/lib/filters/csv";
import { parseCommonFilters, type RawSearchParams } from "@/lib/filters/parse";
import { logAudit } from "@/lib/audit/log";
import type { Currency, Prisma } from "@prisma/client";

interface Ctx {
  params: Promise<{ orgSlug: string }>;
}

export async function GET(req: NextRequest, { params }: Ctx) {
  const { orgSlug } = await params;
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

  const sp: RawSearchParams = {};
  for (const [k, v] of req.nextUrl.searchParams.entries()) sp[k] = v;
  const filters = parseCommonFilters(sp);

  const where: Prisma.ProjectWhereInput = { organizationId: org.id };
  if (filters.status?.length) where.status = { in: filters.status };
  if (filters.customerId) where.customerId = filters.customerId;
  if (filters.currency) where.currency = filters.currency as Currency;
  if (filters.from || filters.to) {
    where.createdAt = {};
    if (filters.from) (where.createdAt as Prisma.DateTimeFilter).gte = filters.from;
    if (filters.to) (where.createdAt as Prisma.DateTimeFilter).lte = filters.to;
  }
  if (filters.q) {
    where.OR = [
      { name: { contains: filters.q, mode: "insensitive" } },
      { description: { contains: filters.q, mode: "insensitive" } },
    ];
  }

  const projects = await prisma.project.findMany({
    where,
    orderBy: { createdAt: "desc" },
    include: {
      customer: { select: { companyName: true, firstName: true, lastName: true, type: true } },
      _count: { select: { tasks: true } },
    },
  });

  const rows = projects.map((p) => ({
    name: p.name,
    customer:
      p.customer.type === "B2B"
        ? p.customer.companyName ?? ""
        : `${p.customer.firstName ?? ""} ${p.customer.lastName ?? ""}`.trim(),
    billingMode: p.billingMode,
    hourlyRate: p.hourlyRate ? p.hourlyRate.toString() : "",
    fixedFee: p.fixedFee ? p.fixedFee.toString() : "",
    currency: p.currency,
    autoInvoice: p.autoInvoice ? "yes" : "no",
    autoInvoiceCycle: p.autoInvoiceCycle,
    statusEmailCycle: p.statusEmailCycle,
    status: p.status,
    tasks: p._count.tasks,
    startDate: p.startDate ? p.startDate.toISOString() : "",
    endDate: p.endDate ? p.endDate.toISOString() : "",
    createdAt: p.createdAt.toISOString(),
  }));

  const csv = rowsToCsv(rows, [
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
    { key: "tasks", label: "Tasks" },
    { key: "startDate", label: "Start Date" },
    { key: "endDate", label: "End Date" },
    { key: "createdAt", label: "Created At" },
  ]);

  await logAudit({
    organizationId: org.id,
    action: "EXPORT",
    entityType: "PROJECT",
    metadata: { rowCount: rows.length, filters: sp as Record<string, unknown> },
  });

  return csvResponse(`projects-${new Date().toISOString().slice(0, 10)}.csv`, csv);
}
