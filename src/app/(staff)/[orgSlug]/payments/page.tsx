import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { PaymentReceiptActions } from "@/components/payments/payment-receipt-actions";
import { formatCurrency, formatDateTime, normalizeNumberFormatStyle } from "@/lib/utils/format";
import { getCachedOrgNumberFormatStyle } from "@/lib/settings/cached-org-settings";
import { FilterBar } from "@/components/filters/filter-bar";
import { parseCommonFilters, type RawSearchParams } from "@/lib/filters/parse";
import type { Currency, PaymentMethod, Prisma } from "@prisma/client";

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<RawSearchParams>;
}

export const metadata = { title: "Payments" };

const METHOD_OPTIONS = (["STRIPE", "BANK_TRANSFER", "MANUAL", "CASH"] as const).map(
  (m) => ({ value: m, label: m.replace("_", " ") })
);

const CURRENCY_OPTIONS = (["EUR", "USD", "GBP", "CHF", "CAD", "AUD"] as const).map(
  (c) => ({ value: c, label: c })
);

const METHOD_BADGES: Record<PaymentMethod, "info" | "success" | "secondary" | "warning"> = {
  STRIPE: "info",
  BANK_TRANSFER: "secondary",
  MANUAL: "warning",
  CASH: "success",
};

export default async function PaymentsPage({ params, searchParams }: Props) {
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

  const where: Prisma.PaymentWhereInput = { organizationId: org.id };

  if (filters.status?.length) {
    where.method = { in: filters.status as PaymentMethod[] };
  }
  if (filters.currency) where.currency = filters.currency as Currency;
  if (filters.from || filters.to) {
    where.paidAt = {};
    if (filters.from) (where.paidAt as Prisma.DateTimeFilter).gte = filters.from;
    if (filters.to) (where.paidAt as Prisma.DateTimeFilter).lte = filters.to;
  }
  if (filters.customerId) {
    where.invoice = { customerId: filters.customerId };
  }
  if (filters.q) {
    where.OR = [
      { stripeChargeId: { contains: filters.q, mode: "insensitive" } },
      { notes: { contains: filters.q, mode: "insensitive" } },
      { invoice: { number: { contains: filters.q, mode: "insensitive" } } },
      {
        invoice: {
          customer: {
            OR: [
              { companyName: { contains: filters.q, mode: "insensitive" } },
              { firstName: { contains: filters.q, mode: "insensitive" } },
              { lastName: { contains: filters.q, mode: "insensitive" } },
            ],
          },
        },
      },
    ];
  }

  const [payments, customers] = await Promise.all([
    prisma.payment.findMany({
      where,
      orderBy: { paidAt: "desc" },
      include: {
        invoice: {
          select: {
            id: true,
            number: true,
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
  ]);

  const customerOptions = customers.map((c) => ({
    value: c.id,
    label:
      c.type === "B2B"
        ? c.companyName ?? "Unnamed"
        : `${c.firstName ?? ""} ${c.lastName ?? ""}`.trim() || "Unnamed",
  }));

  // Sum by currency for the filtered view
  const sumByCurrency = new Map<string, number>();
  for (const p of payments) {
    sumByCurrency.set(
      p.currency,
      (sumByCurrency.get(p.currency) ?? 0) + parseFloat(p.amount.toString())
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">Payments</h1>
          <p className="text-[var(--muted-foreground)] text-sm mt-1">
            {payments.length} payment{payments.length !== 1 ? "s" : ""}
            {sumByCurrency.size > 0 && (
              <span className="ml-2">
                ·{" "}
                {Array.from(sumByCurrency.entries())
                  .map(([cur, amt]) => formatCurrency(amt, cur as Currency, numberFormatStyle))
                  .join(" + ")}
              </span>
            )}
          </p>
        </div>
      </div>

      <FilterBar
        searchPlaceholder="Search by invoice, customer, charge id..."
        statuses={METHOD_OPTIONS}
        customers={customerOptions}
        currencies={CURRENCY_OPTIONS}
        showDateRange
        exportHref={`/${orgSlug}/payments/export`}
      />

      <div className="rounded-lg border border-[var(--border)] bg-[var(--card)]">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Paid At</TableHead>
              <TableHead>Invoice</TableHead>
              <TableHead>Customer</TableHead>
              <TableHead>Method</TableHead>
              <TableHead className="text-right">Amount</TableHead>
              <TableHead>Reference</TableHead>
              <TableHead className="w-[52px] text-right">Receipt</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {payments.length === 0 && (
              <TableRow>
                <TableCell
                  colSpan={7}
                  className="text-center text-[var(--muted-foreground)] py-8"
                >
                  No payments match your filters.
                </TableCell>
              </TableRow>
            )}
            {payments.map((p) => {
              const customer = p.invoice.customer;
              const customerName =
                customer.type === "B2B"
                  ? customer.companyName ?? "Unnamed"
                  : `${customer.firstName ?? ""} ${customer.lastName ?? ""}`.trim() ||
                    "Unnamed";

              return (
                <TableRow key={p.id}>
                  <TableCell className="text-[var(--muted-foreground)]">
                    {formatDateTime(p.paidAt)}
                  </TableCell>
                  <TableCell className="font-mono">
                    <Link
                      href={`/${orgSlug}/invoices/${p.invoice.id}`}
                      className="hover:underline"
                    >
                      {p.invoice.number}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Link
                      href={`/${orgSlug}/customers/${customer.id}`}
                      className="hover:underline text-[var(--muted-foreground)]"
                    >
                      {customerName}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Badge variant={METHOD_BADGES[p.method]}>
                      {p.method.replace("_", " ")}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-right font-medium">
                    {formatCurrency(p.amount.toString(), p.currency, numberFormatStyle)}
                  </TableCell>
                  <TableCell className="text-xs font-mono text-[var(--muted-foreground)]">
                    {p.stripeChargeId ?? p.notes ?? "-"}
                  </TableCell>
                  <TableCell className="text-right">
                    <PaymentReceiptActions
                      orgSlug={orgSlug}
                      paymentId={p.id}
                      receiptViewHref={`/${orgSlug}/receipt/${p.id}`}
                    />
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
