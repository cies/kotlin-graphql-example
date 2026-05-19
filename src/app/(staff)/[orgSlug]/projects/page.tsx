import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Plus } from "lucide-react";
import { formatCurrency, formatDate, normalizeNumberFormatStyle } from "@/lib/utils/format";
import { getCachedOrgNumberFormatStyle } from "@/lib/settings/cached-org-settings";
import { FilterBar } from "@/components/filters/filter-bar";
import { parseCommonFilters, type RawSearchParams } from "@/lib/filters/parse";
import type { Currency, Prisma } from "@prisma/client";

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<RawSearchParams>;
}

export const metadata = { title: "Projects" };

const STATUS_OPTIONS = [
  { value: "NOT_STARTED", label: "Not started" },
  { value: "ACTIVE", label: "Active" },
  { value: "PAUSED", label: "Paused" },
  { value: "ARCHIVED", label: "Archived" },
];

const CURRENCY_OPTIONS = (["EUR", "USD", "GBP", "CHF", "CAD", "AUD"] as const).map(
  (c) => ({ value: c, label: c })
);

export default async function ProjectsPage({ params, searchParams }: Props) {
  const { orgSlug } = await params;
  const sp = await searchParams;
  const filters = parseCommonFilters(sp);
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");
  const numberFormatStyle = normalizeNumberFormatStyle(
    await getCachedOrgNumberFormatStyle(org.id)
  );

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
      {
        customer: {
          OR: [
            { companyName: { contains: filters.q, mode: "insensitive" } },
            { firstName: { contains: filters.q, mode: "insensitive" } },
            { lastName: { contains: filters.q, mode: "insensitive" } },
          ],
        },
      },
    ];
  }

  const [projects, customers] = await Promise.all([
    prisma.project.findMany({
      where,
      orderBy: { createdAt: "desc" },
      include: {
        customer: {
          select: { companyName: true, firstName: true, lastName: true, type: true },
        },
        _count: { select: { tasks: true } },
      },
      take: filters.perPage,
      skip: (filters.page - 1) * filters.perPage,
    }),
    prisma.customer.findMany({
      where: { organizationId: org.id },
      select: { id: true, companyName: true, firstName: true, lastName: true, type: true },
      orderBy: { companyName: "asc" },
    }),
  ]);

  const customerOptions = customers.map((c) => ({
    value: c.id,
    label:
      c.type === "B2B"
        ? c.companyName ?? "Unnamed"
        : `${c.firstName ?? ""} ${c.lastName ?? ""}`.trim() || "Unnamed",
  }));

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">Projects</h1>
          <p className="text-[var(--muted-foreground)] text-sm mt-1">
            {projects.length} project{projects.length !== 1 ? "s" : ""}
          </p>
        </div>
        <Button asChild>
          <Link href={`/${orgSlug}/projects/new`}>
            <Plus className="h-4 w-4" />
            New Project
          </Link>
        </Button>
      </div>

      <FilterBar
        searchPlaceholder="Search by name, customer..."
        statuses={STATUS_OPTIONS}
        customers={customerOptions}
        currencies={CURRENCY_OPTIONS}
        showDateRange
        exportHref={`/${orgSlug}/projects/export`}
      />

      <div className="rounded-lg border border-[var(--border)] bg-[var(--card)]">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Customer</TableHead>
              <TableHead>Billing</TableHead>
              <TableHead>Rate/Fee</TableHead>
              <TableHead>Tasks</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Created</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {projects.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-[var(--muted-foreground)] py-8">
                  No projects match your filters.
                </TableCell>
              </TableRow>
            )}
            {projects.map((p) => {
              const customerName =
                p.customer.type === "B2B"
                  ? p.customer.companyName
                  : `${p.customer.firstName || ""} ${p.customer.lastName || ""}`.trim();

              const rateDisplay =
                p.billingMode === "HOURLY"
                  ? p.hourlyRate
                    ? `${formatCurrency(Number(p.hourlyRate), p.currency, numberFormatStyle)}/h`
                    : "-"
                  : p.fixedFee
                  ? formatCurrency(Number(p.fixedFee), p.currency, numberFormatStyle)
                  : "-";

              return (
                <TableRow key={p.id}>
                  <TableCell className="font-medium">
                    <Link
                      href={`/${orgSlug}/projects/${p.id}`}
                      className="hover:text-[var(--primary)] hover:underline"
                    >
                      {p.name}
                    </Link>
                  </TableCell>
                  <TableCell className="text-[var(--muted-foreground)]">
                    {customerName || "-"}
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline">{p.billingMode}</Badge>
                  </TableCell>
                  <TableCell>{rateDisplay}</TableCell>
                  <TableCell>{p._count.tasks}</TableCell>
                  <TableCell>
                    <Badge
                      variant={
                        p.status === "ACTIVE"
                          ? "success"
                          : p.status === "PAUSED"
                            ? "warning"
                            : p.status === "ARCHIVED"
                              ? "outline"
                              : "secondary"
                      }
                    >
                      {p.status.replace(/_/g, " ")}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-[var(--muted-foreground)]">
                    {formatDate(p.createdAt)}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
