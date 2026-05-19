import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { TimeTracker } from "@/components/time/time-tracker";
import { ManualTimeEntry } from "@/components/time/manual-time-entry";
import { TimeEntriesList } from "@/components/time/time-entries-list";
import { FilterBar } from "@/components/filters/filter-bar";
import {
  parseCommonFilters,
  getTimeBillingStatusFromUrl,
  type RawSearchParams,
} from "@/lib/filters/parse";
import { timeEntryWhereForDateRange } from "@/lib/filters/time-entry-date-range";
import type { Prisma } from "@prisma/client";

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<RawSearchParams>;
}

export const metadata = { title: "Time Tracking" };

export default async function TimePage({ params, searchParams }: Props) {
  const { orgSlug } = await params;
  const sp = await searchParams;
  const billingStatus = getTimeBillingStatusFromUrl(sp);

  if (billingStatus === null) {
    const next = new URLSearchParams();
    for (const [k, raw] of Object.entries(sp)) {
      if (raw == null || k === "status") continue;
      if (Array.isArray(raw)) {
        for (const item of raw) next.append(k, item);
      } else {
        next.set(k, raw);
      }
    }
    next.set("status", "unbilled");
    redirect(`/${orgSlug}/time?${next.toString()}`);
  }

  const filters = parseCommonFilters(sp);
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  // Get running timer for current user
  const runningEntry = await prisma.timeEntry.findFirst({
    where: {
      organizationId: org.id,
      userId: session.user.id,
      startedAt: { not: null },
      endedAt: null,
      manualMinutes: null,
    },
    include: {
      task: { include: { project: { select: { name: true } } } },
    },
  });

  const entryWhere: Prisma.TimeEntryWhereInput = {
    organizationId: org.id,
    userId: session.user.id,
    billed: billingStatus === "billed",
  };

  if (filters.customerId) {
    entryWhere.task = { project: { customerId: filters.customerId } };
  }
  const dateRangeWhere = timeEntryWhereForDateRange(filters.from, filters.to);
  if (dateRangeWhere) {
    const prev = entryWhere.AND;
    entryWhere.AND = [
      ...(prev ? (Array.isArray(prev) ? prev : [prev]) : []),
      dateRangeWhere,
    ];
  }
  if (filters.q) {
    entryWhere.OR = [
      { description: { contains: filters.q, mode: "insensitive" } },
      { task: { title: { contains: filters.q, mode: "insensitive" } } },
      {
        task: {
          project: { name: { contains: filters.q, mode: "insensitive" } },
        },
      },
    ];
  }

  const entries = await prisma.timeEntry.findMany({
    where: entryWhere,
    orderBy: [{ workSortAt: "asc" }, { id: "asc" }],
    take: filters.perPage,
    skip: (filters.page - 1) * filters.perPage,
    include: {
      task: {
        select: {
          id: true,
          title: true,
          project: {
            select: {
              name: true,
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
    },
  });

  const customers = await prisma.customer.findMany({
    where: { organizationId: org.id },
    select: { id: true, companyName: true, firstName: true, lastName: true, type: true },
    orderBy: { companyName: "asc" },
  });

  const customerOptions = customers.map((c) => ({
    value: c.id,
    label:
      c.type === "B2B"
        ? c.companyName ?? "Unnamed"
        : `${c.firstName ?? ""} ${c.lastName ?? ""}`.trim() || "Unnamed",
  }));

  // Get all tasks for selection
  const tasks = await prisma.task.findMany({
    where: { organizationId: org.id, status: { not: "CANCELLED" } },
    include: {
      project: { select: { name: true } },
    },
    orderBy: [{ project: { name: "asc" } }, { title: "asc" }],
  });

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">Time Tracking</h1>
        <p className="text-[var(--muted-foreground)] text-sm mt-1">
          Track time spent on tasks
        </p>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="lg:col-span-1 space-y-4">
          <TimeTracker
            orgSlug={orgSlug}
            runningEntry={
              runningEntry
                ? {
                    id: runningEntry.id,
                    startedAt: runningEntry.startedAt!,
                    taskTitle: runningEntry.task.title,
                    projectName: runningEntry.task.project.name,
                  }
                : null
            }
            tasks={tasks.map((t) => ({
              id: t.id,
              title: t.title,
              projectName: t.project.name,
            }))}
          />

          <ManualTimeEntry
            orgSlug={orgSlug}
            tasks={tasks.map((t) => ({
              id: t.id,
              title: t.title,
              projectName: t.project.name,
            }))}
          />
        </div>

        <div className="lg:col-span-2 space-y-4">
          <FilterBar
            searchPlaceholder="Search task / project / description..."
            customers={customerOptions}
            showDateRange
            exportHref={`/${orgSlug}/time/export`}
            statusDefaultIgnored="unbilled"
            resetSearchDefaults="status=unbilled"
          />
          <TimeEntriesList
            orgSlug={orgSlug}
            entries={entries.map((e) => {
              const customerName =
                e.task.project.customer.type === "B2B"
                  ? e.task.project.customer.companyName
                  : `${e.task.project.customer.firstName || ""} ${e.task.project.customer.lastName || ""}`.trim();
              return {
                id: e.id,
                taskId: e.task.id,
                taskTitle: e.task.title,
                projectName: e.task.project.name,
                customerName: customerName || "-",
                startedAt: e.startedAt,
                endedAt: e.endedAt,
                manualMinutes: e.manualMinutes,
                loggedDate: e.loggedDate,
                description: e.description,
                billed: e.billed,
                createdAt: e.createdAt,
              };
            })}
          />
        </div>
      </div>
    </div>
  );
}
