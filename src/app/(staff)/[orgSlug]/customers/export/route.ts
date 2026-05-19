import { NextRequest } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { csvResponse, rowsToCsv } from "@/lib/filters/csv";
import { parseCommonFilters, type RawSearchParams } from "@/lib/filters/parse";
import { logAudit } from "@/lib/audit/log";
import type { Prisma, Currency } from "@prisma/client";

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

  const where: Prisma.CustomerWhereInput = { organizationId: org.id };
  if (filters.status?.length) where.type = { in: filters.status as ("B2B" | "B2C")[] };
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
  });

  const rows = customers.map((c) => ({
    type: c.type,
    name:
      c.type === "B2B"
        ? c.companyName ?? ""
        : `${c.firstName ?? ""} ${c.lastName ?? ""}`.trim(),
    companyName: c.companyName ?? "",
    firstName: c.firstName ?? "",
    lastName: c.lastName ?? "",
    email: c.email ?? "",
    phone: c.phone ?? "",
    vat: c.vat ?? "",
    preferredCurrency: c.preferredCurrency,
    createdAt: c.createdAt.toISOString(),
  }));

  const csv = rowsToCsv(rows, [
    { key: "type", label: "Type" },
    { key: "name", label: "Name" },
    { key: "companyName", label: "Company Name" },
    { key: "firstName", label: "First Name" },
    { key: "lastName", label: "Last Name" },
    { key: "email", label: "Email" },
    { key: "phone", label: "Phone" },
    { key: "vat", label: "VAT" },
    { key: "preferredCurrency", label: "Currency" },
    { key: "createdAt", label: "Created At" },
  ]);

  await logAudit({
    organizationId: org.id,
    action: "EXPORT",
    entityType: "CUSTOMER",
    metadata: { rowCount: rows.length, filters: sp as Record<string, unknown> },
  });

  return csvResponse(`customers-${new Date().toISOString().slice(0, 10)}.csv`, csv);
}
