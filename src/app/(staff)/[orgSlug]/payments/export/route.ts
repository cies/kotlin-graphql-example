import { NextRequest } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { csvResponse, rowsToCsv } from "@/lib/filters/csv";
import { parseCommonFilters, type RawSearchParams } from "@/lib/filters/parse";
import { logAudit } from "@/lib/audit/log";
import type { Currency, PaymentMethod, Prisma } from "@prisma/client";

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

  const where: Prisma.PaymentWhereInput = { organizationId: org.id };
  if (filters.status?.length) where.method = { in: filters.status as PaymentMethod[] };
  if (filters.currency) where.currency = filters.currency as Currency;
  if (filters.from || filters.to) {
    where.paidAt = {};
    if (filters.from) (where.paidAt as Prisma.DateTimeFilter).gte = filters.from;
    if (filters.to) (where.paidAt as Prisma.DateTimeFilter).lte = filters.to;
  }
  if (filters.customerId) where.invoice = { customerId: filters.customerId };
  if (filters.q) {
    where.OR = [
      { stripeChargeId: { contains: filters.q, mode: "insensitive" } },
      { notes: { contains: filters.q, mode: "insensitive" } },
      { invoice: { number: { contains: filters.q, mode: "insensitive" } } },
    ];
  }

  const payments = await prisma.payment.findMany({
    where,
    orderBy: { paidAt: "desc" },
    include: {
      invoice: {
        select: {
          number: true,
          customer: {
            select: { companyName: true, firstName: true, lastName: true, type: true },
          },
        },
      },
    },
  });

  const rows = payments.map((p) => ({
    paidAt: p.paidAt.toISOString(),
    invoiceNumber: p.invoice.number,
    customer:
      p.invoice.customer.type === "B2B"
        ? p.invoice.customer.companyName ?? ""
        : `${p.invoice.customer.firstName ?? ""} ${p.invoice.customer.lastName ?? ""}`.trim(),
    method: p.method,
    amount: p.amount.toString(),
    currency: p.currency,
    stripeChargeId: p.stripeChargeId ?? "",
    notes: p.notes ?? "",
  }));

  const csv = rowsToCsv(rows, [
    { key: "paidAt", label: "Paid At" },
    { key: "invoiceNumber", label: "Invoice Number" },
    { key: "customer", label: "Customer" },
    { key: "method", label: "Method" },
    { key: "amount", label: "Amount" },
    { key: "currency", label: "Currency" },
    { key: "stripeChargeId", label: "Stripe Charge ID" },
    { key: "notes", label: "Notes" },
  ]);

  await logAudit({
    organizationId: org.id,
    action: "EXPORT",
    entityType: "PAYMENT",
    metadata: { rowCount: rows.length, filters: sp as Record<string, unknown> },
  });

  return csvResponse(`payments-${new Date().toISOString().slice(0, 10)}.csv`, csv);
}
