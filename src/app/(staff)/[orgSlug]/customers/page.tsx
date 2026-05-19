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
import { formatDate } from "@/lib/utils/format";
import type { Currency, Prisma } from "@prisma/client";
import { FilterBar } from "@/components/filters/filter-bar";
import { parseCommonFilters, type RawSearchParams } from "@/lib/filters/parse";

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<RawSearchParams>;
}

export const metadata = { title: "Customers" };

const TYPE_OPTIONS = [
  { value: "B2B", label: "B2B (Business)" },
  { value: "B2C", label: "B2C (Consumer)" },
];

const CURRENCY_OPTIONS = (["EUR", "USD", "GBP", "CHF", "CAD", "AUD"] as const).map(
  (c) => ({ value: c, label: c })
);

export default async function CustomersPage({ params, searchParams }: Props) {
  const { orgSlug } = await params;
  const sp = await searchParams;
  const filters = parseCommonFilters(sp);
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  const where: Prisma.CustomerWhereInput = { organizationId: org.id };

  if (filters.status?.length) {
    where.type = { in: filters.status as ("B2B" | "B2C")[] };
  }
  if (filters.currency) where.preferredCurrency = filters.currency as Currency;
  if (filters.from || filters.to) {
    where.createdAt = {};
    if (filters.from) (where.createdAt as Prisma.DateTimeFilter).gte = filters.from;
    if (filters.to) (where.createdAt as Prisma.DateTimeFilter).lte = filters.to;
  }
  if (filters.q) {
    where.OR = [
      { companyName: { contains: filters.q, mode: "insensitive" } },
      { firstName: { contains: filters.q, mode: "insensitive" } },
      { lastName: { contains: filters.q, mode: "insensitive" } },
      { email: { contains: filters.q, mode: "insensitive" } },
      { vat: { contains: filters.q, mode: "insensitive" } },
    ];
  }

  const customers = await prisma.customer.findMany({
    where,
    orderBy: { createdAt: "desc" },
    include: {
      _count: { select: { projects: true, invoices: true } },
    },
    take: filters.perPage,
    skip: (filters.page - 1) * filters.perPage,
  });

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">Customers</h1>
          <p className="text-[var(--muted-foreground)] text-sm mt-1">
            {customers.length} customer{customers.length !== 1 ? "s" : ""}
          </p>
        </div>
        <Button asChild>
          <Link href={`/${orgSlug}/customers/new`}>
            <Plus className="h-4 w-4" />
            New Customer
          </Link>
        </Button>
      </div>

      <FilterBar
        searchPlaceholder="Search by name, email, VAT..."
        statuses={TYPE_OPTIONS}
        currencies={CURRENCY_OPTIONS}
        showDateRange
        exportHref={`/${orgSlug}/customers/export`}
      />

      <div className="rounded-lg border border-[var(--border)] bg-[var(--card)]">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Email</TableHead>
              <TableHead>Currency</TableHead>
              <TableHead>Projects</TableHead>
              <TableHead>Invoices</TableHead>
              <TableHead>Created</TableHead>
              <TableHead className="w-10" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {customers.length === 0 && (
              <TableRow>
                <TableCell colSpan={8} className="text-center text-[var(--muted-foreground)] py-8">
                  No customers match your filters.
                </TableCell>
              </TableRow>
            )}
            {customers.map((c) => (
              <TableRow key={c.id}>
                <TableCell className="font-medium">
                  <Link
                    href={`/${orgSlug}/customers/${c.id}`}
                    className="hover:text-[var(--primary)] hover:underline"
                  >
                    {c.type === "B2B"
                      ? c.companyName || "Unnamed"
                      : `${c.firstName || ""} ${c.lastName || ""}`.trim() || "Unnamed"}
                  </Link>
                </TableCell>
                <TableCell>
                  <Badge variant={c.type === "B2B" ? "info" : "secondary"}>
                    {c.type}
                  </Badge>
                </TableCell>
                <TableCell className="text-[var(--muted-foreground)]">
                  {c.email || "-"}
                </TableCell>
                <TableCell className="text-[var(--muted-foreground)]">
                  {c.preferredCurrency}
                </TableCell>
                <TableCell>{c._count.projects}</TableCell>
                <TableCell>{c._count.invoices}</TableCell>
                <TableCell className="text-[var(--muted-foreground)]">
                  {formatDate(c.createdAt)}
                </TableCell>
                <TableCell>
                  <Button variant="ghost" size="icon" asChild>
                    <Link href={`/${orgSlug}/customers/${c.id}`}>
                      <ExternalLink className="h-4 w-4" />
                    </Link>
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
