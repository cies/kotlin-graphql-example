import { NextRequest } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { csvResponse, rowsToCsv } from "@/lib/filters/csv";
import { parseCommonFilters, type RawSearchParams } from "@/lib/filters/parse";
import { logAudit } from "@/lib/audit/log";
import type { Prisma, InvoiceStatus, Currency } from "@prisma/client";

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

  const where: Prisma.InvoiceWhereInput = { organizationId: org.id };
  if (filters.status?.length) where.status = { in: filters.status as InvoiceStatus[] };
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

  const invoices = await prisma.invoice.findMany({
    where,
    orderBy: { createdAt: "desc" },
    include: {
      customer: {
        select: {
          companyName: true,
          firstName: true,
          lastName: true,
          email: true,
          type: true,
        },
      },
    },
  });

  const rows = invoices.map((inv) => {
    const name =
      inv.customer.type === "B2B"
        ? inv.customer.companyName ?? ""
        : `${inv.customer.firstName ?? ""} ${inv.customer.lastName ?? ""}`.trim();
    return {
      number: inv.number,
      status: inv.status,
      customer: name,
      customerEmail: inv.customer.email ?? "",
      currency: inv.currency,
      subtotal: inv.subtotal.toString(),
      vat: inv.vat.toString(),
      total: inv.total.toString(),
      amountPaid: inv.amountPaid.toString(),
      issuedAt: inv.issuedAt.toISOString(),
      dueDate: inv.dueDate ? inv.dueDate.toISOString() : "",
      paidAt: inv.paidAt ? inv.paidAt.toISOString() : "",
      notes: inv.notes ?? "",
    };
  });

  const csv = rowsToCsv(rows, [
    { key: "number", label: "Number" },
    { key: "status", label: "Status" },
    { key: "customer", label: "Customer" },
    { key: "customerEmail", label: "Customer Email" },
    { key: "currency", label: "Currency" },
    { key: "subtotal", label: "Subtotal" },
    { key: "vat", label: "VAT" },
    { key: "total", label: "Total" },
    { key: "amountPaid", label: "Amount Paid" },
    { key: "issuedAt", label: "Issued At" },
    { key: "dueDate", label: "Due Date" },
    { key: "paidAt", label: "Paid At" },
    { key: "notes", label: "Notes" },
  ]);

  await logAudit({
    organizationId: org.id,
    action: "EXPORT",
    entityType: "INVOICE",
    metadata: { rowCount: rows.length, filters: sp as Record<string, unknown> },
  });

  return csvResponse(`invoices-${new Date().toISOString().slice(0, 10)}.csv`, csv);
}
