import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatDateTime } from "@/lib/utils/format";
import { FilterBar } from "@/components/filters/filter-bar";
import { parseCommonFilters, type RawSearchParams } from "@/lib/filters/parse";
import type { Prisma } from "@prisma/client";

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<RawSearchParams>;
}

export const metadata = { title: "Audit Log" };

const ENTITY_OPTIONS = [
  "CUSTOMER",
  "CUSTOMER_CONTACT",
  "INVOICE",
  "INVOICE_NOTE",
  "INVOICE_ATTACHMENT",
  "PAYMENT",
  "CONTRACT",
  "PROJECT",
  "TASK",
  "TIME_ENTRY",
  "REMINDER",
  "SETTINGS",
  "EMAIL_TEMPLATE",
  "AUTH",
  "SUBSCRIPTION",
].map((v) => ({ value: v, label: v.replace("_", " ") }));

const ACTION_BADGES: Record<string, "info" | "success" | "warning" | "destructive" | "secondary"> = {
  CREATE: "success",
  UPDATE: "info",
  DELETE: "destructive",
  SEND: "info",
  VOID: "warning",
  PAY: "success",
  SIGN: "success",
  LOGIN: "secondary",
  LOGOUT: "secondary",
  RESET: "warning",
  TEST_SEND: "secondary",
  STATUS_CHANGE: "info",
  EXPORT: "secondary",
};

export default async function AuditLogPage({ params, searchParams }: Props) {
  const { orgSlug } = await params;
  const sp = await searchParams;
  const filters = parseCommonFilters(sp);
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  const where: Prisma.AuditLogWhereInput = { organizationId: org.id };
  if (filters.status?.length) where.entityType = { in: filters.status };
  if (filters.from || filters.to) {
    where.createdAt = {};
    if (filters.from) (where.createdAt as Prisma.DateTimeFilter).gte = filters.from;
    if (filters.to) (where.createdAt as Prisma.DateTimeFilter).lte = filters.to;
  }
  if (filters.q) {
    where.OR = [
      { action: { contains: filters.q, mode: "insensitive" } },
      { actorEmail: { contains: filters.q, mode: "insensitive" } },
      { entityId: { contains: filters.q, mode: "insensitive" } },
      { entityType: { contains: filters.q, mode: "insensitive" } },
      { ipAddress: { contains: filters.q, mode: "insensitive" } },
    ];
  }

  const [entries, totalCount] = await Promise.all([
    prisma.auditLog.findMany({
      where,
      orderBy: { createdAt: "desc" },
      take: filters.perPage,
      skip: (filters.page - 1) * filters.perPage,
      include: {
        user: { select: { name: true, email: true } },
      },
    }),
    prisma.auditLog.count({ where }),
  ]);

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">Audit Log</h1>
        <p className="text-[var(--muted-foreground)] text-sm mt-1">
          {totalCount.toLocaleString()} event{totalCount === 1 ? "" : "s"} recorded
          (showing {entries.length})
        </p>
      </div>

      <FilterBar
        searchPlaceholder="Search action, actor, entity id, IP..."
        statuses={ENTITY_OPTIONS}
        showDateRange
      />

      <div className="rounded-lg border border-[var(--border)] bg-[var(--card)]">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>When</TableHead>
              <TableHead>Actor</TableHead>
              <TableHead>Action</TableHead>
              <TableHead>Entity</TableHead>
              <TableHead>Entity ID</TableHead>
              <TableHead>IP</TableHead>
              <TableHead>Metadata</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {entries.length === 0 && (
              <TableRow>
                <TableCell
                  colSpan={7}
                  className="text-center text-[var(--muted-foreground)] py-8"
                >
                  No audit entries match your filters.
                </TableCell>
              </TableRow>
            )}
            {entries.map((entry) => (
              <TableRow key={entry.id}>
                <TableCell className="text-[var(--muted-foreground)] whitespace-nowrap">
                  {formatDateTime(entry.createdAt)}
                </TableCell>
                <TableCell className="text-sm">
                  {entry.user?.name ?? entry.actorEmail ?? "system"}
                  {entry.actorType && (
                    <span className="ml-1 text-xs text-[var(--muted-foreground)]">
                      ({entry.actorType})
                    </span>
                  )}
                </TableCell>
                <TableCell>
                  <Badge variant={ACTION_BADGES[entry.action] ?? "secondary"}>
                    {entry.action}
                  </Badge>
                </TableCell>
                <TableCell className="font-medium">{entry.entityType}</TableCell>
                <TableCell className="font-mono text-xs text-[var(--muted-foreground)]">
                  {entry.entityId ?? "-"}
                </TableCell>
                <TableCell className="font-mono text-xs text-[var(--muted-foreground)]">
                  {entry.ipAddress ?? "-"}
                </TableCell>
                <TableCell className="max-w-md">
                  {entry.metadata ? (
                    <pre className="text-[11px] text-[var(--muted-foreground)] whitespace-pre-wrap break-all overflow-hidden line-clamp-3">
                      {JSON.stringify(entry.metadata)}
                    </pre>
                  ) : (
                    <span className="text-[var(--muted-foreground)]">-</span>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      {totalCount > filters.perPage && (
        <p className="text-xs text-[var(--muted-foreground)] mt-3 text-center">
          Showing {entries.length} of {totalCount.toLocaleString()} entries.
          Refine filters to see different ranges.
        </p>
      )}
    </div>
  );
}
