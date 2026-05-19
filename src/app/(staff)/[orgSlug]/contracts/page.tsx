import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { sendContractForSignature } from "@/lib/actions/contracts";
import { formatDateTime } from "@/lib/utils/format";
import { FilterBar } from "@/components/filters/filter-bar";
import { parseCommonFilters, type RawSearchParams } from "@/lib/filters/parse";
import type { ContractStatus, Prisma } from "@prisma/client";

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<RawSearchParams>;
}

export const metadata = { title: "Contracts" };

const STATUS_OPTIONS = (["DRAFT", "SENT", "SIGNED", "VOID"] as const).map((s) => ({
  value: s,
  label: s,
}));

export default async function ContractsPage({ params, searchParams }: Props) {
  const { orgSlug } = await params;
  const sp = await searchParams;
  const filters = parseCommonFilters(sp);
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) redirect("/auth/login");

  const where: Prisma.ContractWhereInput = { organizationId: org.id };
  if (filters.status?.length) where.status = { in: filters.status as ContractStatus[] };
  if (filters.customerId) where.customerId = filters.customerId;
  if (filters.from || filters.to) {
    where.createdAt = {};
    if (filters.from) (where.createdAt as Prisma.DateTimeFilter).gte = filters.from;
    if (filters.to) (where.createdAt as Prisma.DateTimeFilter).lte = filters.to;
  }
  if (filters.q) {
    where.OR = [
      { title: { contains: filters.q, mode: "insensitive" } },
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

  const [contracts, customers] = await Promise.all([
    prisma.contract.findMany({
      where,
      include: { customer: true, signatures: { orderBy: { signedAt: "desc" }, take: 1 } },
      orderBy: { updatedAt: "desc" },
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
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Contracts</h1>
          <p className="text-sm text-[var(--muted-foreground)] mt-1">
            Draft, send, and track signatures.
          </p>
        </div>
        <Button asChild>
          <Link href={`/${orgSlug}/contracts/new`}>New contract</Link>
        </Button>
      </div>

      <FilterBar
        searchPlaceholder="Search by title or customer..."
        statuses={STATUS_OPTIONS}
        customers={customerOptions}
        showDateRange
      />

      <Card>
        <CardHeader>
          <CardTitle>Contracts</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {contracts.length === 0 && (
            <p className="text-sm text-[var(--muted-foreground)]">No contracts match your filters.</p>
          )}
          {contracts.map((contract) => {
            const customerName =
              contract.customer.companyName ||
              `${contract.customer.firstName || ""} ${contract.customer.lastName || ""}`.trim() ||
              "Customer";

            return (
              <div key={contract.id} className="rounded-md border border-[var(--border)] p-3">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <Link href={`/${orgSlug}/contracts/${contract.id}`} className="font-medium hover:underline">
                      {contract.title}
                    </Link>
                    <p className="text-sm text-[var(--muted-foreground)]">{customerName}</p>
                    <p className="text-xs text-[var(--muted-foreground)] mt-1">
                      Updated {formatDateTime(contract.updatedAt)}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge variant={contract.status === "SIGNED" ? "success" : contract.status === "SENT" ? "info" : "secondary"}>
                      {contract.status}
                    </Badge>
                    <Button variant="outline" size="sm" asChild>
                      <a href={`/api/pdf/contract/${contract.id}`} target="_blank" rel="noreferrer">
                        PDF
                      </a>
                    </Button>
                    {contract.status !== "SIGNED" && (
                      <form
                        action={async () => {
                          "use server";
                          await sendContractForSignature(orgSlug, contract.id);
                        }}
                      >
                        <Button size="sm">Send for signature</Button>
                      </form>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </CardContent>
      </Card>
    </div>
  );
}
