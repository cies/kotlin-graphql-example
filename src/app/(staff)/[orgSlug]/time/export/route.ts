import { NextRequest } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { csvResponse, rowsToCsv } from "@/lib/filters/csv";
import {
  parseCommonFilters,
  parseTimeEntryBillingFilter,
  type RawSearchParams,
} from "@/lib/filters/parse";
import { timeEntryWhereForDateRange } from "@/lib/filters/time-entry-date-range";
import { logAudit } from "@/lib/audit/log";
import type { Prisma } from "@prisma/client";

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
  const billing = parseTimeEntryBillingFilter(sp);

  const where: Prisma.TimeEntryWhereInput = {
    organizationId: org.id,
    billed: billing === "billed",
  };
  if (filters.customerId) where.task = { project: { customerId: filters.customerId } };
  const dateRangeWhere = timeEntryWhereForDateRange(filters.from, filters.to);
  if (dateRangeWhere) {
    const prev = where.AND;
    where.AND = [...(prev ? (Array.isArray(prev) ? prev : [prev]) : []), dateRangeWhere];
  }
  if (filters.q) {
    where.OR = [
      { description: { contains: filters.q, mode: "insensitive" } },
      { task: { title: { contains: filters.q, mode: "insensitive" } } },
      { task: { project: { name: { contains: filters.q, mode: "insensitive" } } } },
    ];
  }

  const entries = await prisma.timeEntry.findMany({
    where,
    orderBy: [{ workSortAt: "asc" }, { id: "asc" }],
    include: {
      task: {
        include: {
          project: {
            include: {
              customer: {
                select: {
                  companyName: true,
                  firstName: true,
                  lastName: true,
                  type: true,
                },
              },
            },
          },
        },
      },
      user: { select: { name: true, email: true } },
    },
  });

  const rows = entries.map((e) => {
    let minutes: number;
    if (e.manualMinutes != null) {
      minutes = e.manualMinutes;
    } else if (e.startedAt && e.endedAt) {
      minutes = Math.round((e.endedAt.getTime() - e.startedAt.getTime()) / 60000);
    } else {
      minutes = 0;
    }
    return {
      loggedDate: e.loggedDate ? e.loggedDate.toISOString().slice(0, 10) : "",
      createdAt: e.createdAt.toISOString(),
      user: e.user.name ?? e.user.email,
      project: e.task.project.name,
      task: e.task.title,
      customer:
        e.task.project.customer.type === "B2B"
          ? e.task.project.customer.companyName ?? ""
          : `${e.task.project.customer.firstName ?? ""} ${e.task.project.customer.lastName ?? ""}`.trim(),
      startedAt: e.startedAt ? e.startedAt.toISOString() : "",
      endedAt: e.endedAt ? e.endedAt.toISOString() : "",
      minutes,
      hours: (minutes / 60).toFixed(2),
      billed: e.billed,
      hourlyRate: e.hourlyRateSnapshot ? e.hourlyRateSnapshot.toString() : "",
      description: e.description ?? "",
    };
  });

  const csv = rowsToCsv(rows, [
    { key: "loggedDate", label: "Work Date" },
    { key: "createdAt", label: "Created At" },
    { key: "user", label: "User" },
    { key: "customer", label: "Customer" },
    { key: "project", label: "Project" },
    { key: "task", label: "Task" },
    { key: "startedAt", label: "Started At" },
    { key: "endedAt", label: "Ended At" },
    { key: "minutes", label: "Minutes" },
    { key: "hours", label: "Hours" },
    { key: "billed", label: "Billed" },
    { key: "hourlyRate", label: "Hourly Rate Snapshot" },
    { key: "description", label: "Description" },
  ]);

  await logAudit({
    organizationId: org.id,
    action: "EXPORT",
    entityType: "TIME_ENTRY",
    metadata: { rowCount: rows.length, filters: sp as Record<string, unknown> },
  });

  return csvResponse(`time-entries-${new Date().toISOString().slice(0, 10)}.csv`, csv);
}
