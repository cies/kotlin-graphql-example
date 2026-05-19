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
import { Plus, ExternalLink } from "lucide-react";
import { formatDate, formatCurrency, normalizeNumberFormatStyle } from "@/lib/utils/format";
import { getCachedOrgNumberFormatStyle } from "@/lib/settings/cached-org-settings";
import type { InvoiceStatus, Currency, Prisma } from "@prisma/client";
import { FilterBar } from "@/components/filters/filter-bar";
import { parseCommonFilters, type RawSearchParams } from "@/lib/filters/parse";
import { InvoiceStatusSummary } from "@/components/invoices/invoice-status-summary";

const ALL_INVOICE_STATUSES = [
  "DRAFT",
  "SENT",
  "PARTIAL",
  "PAID",
  "OVERDUE",
  "VOID",
  "REFUNDED",
  "CHARGEBACK",
] as const satisfies readonly InvoiceStatus[];

function emptyStatusCounts(): Record<InvoiceStatus, number> {
  return Object.fromEntries(ALL_INVOICE_STATUSES.map((s) => [s, 0])) as Record<
    InvoiceStatus,
    number
  >;
}

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<RawSearchParams>;
}

export const metadata = { title: "Invoices" };

const STATUS_VARIANTS: Record<
  InvoiceStatus,
  "default" | "secondary" | "info" | "success" | "warning" | "destructive"
> = {
  DRAFT: "secondary",
  SENT: "info",
  PARTIAL: "warning",
  PAID: "success",
  OVERDUE: "destructive",
  VOID: "secondary",
  REFUNDED: "warning",
  CHARGEBACK: "destructive",
};

const STATUS_OPTIONS = (
  [
    "DRAFT",
    "SENT",
    "PARTIAL",
    "PAID",
    "OVERDUE",
    "VOID",
    "REFUNDED",
    "CHARGEBACK",
  ] as const
).map((s) => ({ value: s, label: s }));

const CURRENCY_OPTIONS = (["EUR", "USD", "GBP", "CHF", "CAD", "AUD"] as const).map(
  (c) => ({ value: c, label: c })
);

export default async function InvoicesPage({ params, searchParams }: Props) {
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

  const where: Prisma.InvoiceWhereInput = { organizationId: org.id };

  if (filters.status && filters.status.length > 0) {
    where.status = { in: filters.status as InvoiceStatus[] };
  }
  if (filters.customerId) where.customerId = filters.customerId;
  if (filters.currency) where.currency = filters.currency as Currency;
  if (filters.from || filters.to) {
    where.issuedAt = {};
    if (filters.from) (where.issuedAt as Prisma.DateTimeFilter).gte = filters.from;
    if (filters.to) (where.issuedAt as Prisma.DateTimeFilter).lte = filters.to;
  }
  if (filters.q) {
    where.OR = [
      { number: { contains: filters.q, mode: "insensitive" } },
      { notes: { contains: filters.q, mode: "insensitive" } },
      {
        customer: {
          OR: [
            { companyName: { contains: filters.q, mode: "insensitive" } },
            { firstName: { contains: filters.q, mode: "insensitive" } },
            { lastName: { contains: filters.q, mode: "insensitive" } },
            { email: { contains: filters.q, mode: "insensitive" } },
          ],
        },
      },
    ];
  }

  const [invoices, customers, statusGroups] = await Promise.all([
    prisma.invoice.findMany({
      where,
      orderBy: { createdAt: "desc" },
      include: {
        customer: {
          select: {
            id: true,
            companyName: true,
            firstName: true,
            lastName: true,
            type: true,
          },
        },
      },
      take: filters.perPage,
      skip: (filters.page - 1) * filters.perPage,
    }),
    prisma.customer.findMany({
      where: { organizationId: org.id },
      select: { id: true, companyName: true, firstName: true, lastName: true, type: true },
      orderBy: { companyName: "asc" },
    }),
    prisma.invoice.groupBy({
      by: ["status"],
      where: { organizationId: org.id },
      _count: { _all: true },
    }),
  ]);

  const statusCounts = emptyStatusCounts();
  for (const row of statusGroups) {
    statusCounts[row.status] = row._count._all;
  }

  const totalOutstanding = invoices
    .filter((i) => ["SENT", "PARTIAL", "OVERDUE"].includes(i.status))
    .reduce(
      (sum, i) =>
        sum + parseFloat(i.total.toString()) - parseFloat(i.amountPaid.toString()),
      0
    );

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
          <h1 className="text-2xl font-bold">Invoices</h1>
          <p className="text-[var(--muted-foreground)] text-sm mt-1">
            {invoices.length} invoice{invoices.length !== 1 ? "s" : ""}
            {totalOutstanding > 0 && (
              <span className="ml-2 text-orange-600 font-medium">
                · {formatCurrency(totalOutstanding, "EUR", numberFormatStyle)} outstanding (filtered)
              </span>
            )}
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" asChild>
            <Link href={`/${orgSlug}/invoices/recurring`}>Recurring Rules</Link>
          </Button>
          <Button asChild>
            <Link href={`/${orgSlug}/invoices/new`}>
              <Plus className="h-4 w-4" />
              New Invoice
            </Link>
          </Button>
        </div>
      </div>

      <InvoiceStatusSummary
        orgSlug={orgSlug}
        counts={statusCounts}
        activeStatusFilter={filters.status ?? []}
      />

      <FilterBar
        searchPlaceholder="Search invoice number, customer, notes..."
        statuses={STATUS_OPTIONS}
        customers={customerOptions}
        currencies={CURRENCY_OPTIONS}
        showDateRange
        exportHref={`/${orgSlug}/invoices/export`}
      />

      <div className="rounded-lg border border-[var(--border)] bg-[var(--card)]">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Number</TableHead>
              <TableHead>Customer</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Issue Date</TableHead>
              <TableHead>Due Date</TableHead>
              <TableHead className="text-right">Amount</TableHead>
              <TableHead className="text-right">Paid</TableHead>
              <TableHead className="w-10" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {invoices.length === 0 && (
              <TableRow>
                <TableCell
                  colSpan={8}
                  className="text-center text-[var(--muted-foreground)] py-8"
                >
                  No invoices match your filters.
                </TableCell>
              </TableRow>
            )}
            {invoices.map((inv) => {
              const customerName =
                inv.customer.type === "B2B"
                  ? inv.customer.companyName ?? "Unnamed"
                  : `${inv.customer.firstName ?? ""} ${inv.customer.lastName ?? ""}`.trim() ||
                    "Unnamed";

              return (
                <TableRow key={inv.id}>
                  <TableCell className="font-mono font-medium">
                    <Link
                      href={`/${orgSlug}/invoices/${inv.id}`}
                      className="hover:text-[var(--primary)] hover:underline"
                    >
                      {inv.number}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Link
                      href={`/${orgSlug}/customers/${inv.customer.id}`}
                      className="hover:underline text-[var(--muted-foreground)]"
                    >
                      {customerName}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Badge variant={STATUS_VARIANTS[inv.status]}>{inv.status}</Badge>
                  </TableCell>
                  <TableCell className="text-[var(--muted-foreground)]">
                    {formatDate(inv.issuedAt)}
                  </TableCell>
                  <TableCell className="text-[var(--muted-foreground)]">
                    {formatDate(inv.dueDate)}
                  </TableCell>
                  <TableCell className="text-right font-medium">
                    {formatCurrency(inv.total.toString(), inv.currency, numberFormatStyle)}
                  </TableCell>
                  <TableCell className="text-right text-[var(--muted-foreground)]">
                    {parseFloat(inv.amountPaid.toString()) > 0
                      ? formatCurrency(inv.amountPaid.toString(), inv.currency, numberFormatStyle)
                      : "-"}
                  </TableCell>
                  <TableCell>
                    <Button variant="ghost" size="icon" asChild>
                      <Link href={`/${orgSlug}/invoices/${inv.id}`}>
                        <ExternalLink className="h-4 w-4" />
                      </Link>
                    </Button>
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
