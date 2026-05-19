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
import { ArrowLeft, Pencil } from "lucide-react";
import { formatDate } from "@/lib/utils/format";
import { deleteRecurringRule, updateRecurringRule } from "@/lib/actions/invoices";
import { RecurringRuleActions } from "@/components/invoices/recurring-rule-actions";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export const metadata = { title: "Recurring Invoice Rules" };

export default async function RecurringRulesPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  const rules = await prisma.recurringInvoiceRule.findMany({
    where: { organizationId: org.id },
    orderBy: { createdAt: "desc" },
    include: {
      organization: {
        include: {
          customers: {
            where: {},
            select: { id: true, companyName: true, firstName: true, lastName: true, type: true },
          },
        },
      },
    },
  });

  const customers = await prisma.customer.findMany({
    where: { organizationId: org.id },
    select: { id: true, companyName: true, firstName: true, lastName: true, type: true },
  });

  const customerMap = new Map(
    customers.map((c) => [
      c.id,
      c.type === "B2B"
        ? c.companyName ?? "Unnamed"
        : `${c.firstName ?? ""} ${c.lastName ?? ""}`.trim() || "Unnamed",
    ])
  );

  return (
    <div>
      <div className="flex items-center gap-4 mb-6">
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/${orgSlug}/invoices`}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <div>
          <h1 className="text-2xl font-bold">Recurring Invoice Rules</h1>
          <p className="text-[var(--muted-foreground)] text-sm mt-1">
            Automatically create invoices on a schedule
          </p>
        </div>
      </div>

      <div className="rounded-lg border border-[var(--border)] bg-[var(--card)]">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Customer</TableHead>
              <TableHead>Interval</TableHead>
              <TableHead>Next Run</TableHead>
              <TableHead>End Date</TableHead>
              <TableHead>Status</TableHead>
              <TableHead className="w-36 text-right">Template</TableHead>
              <TableHead className="w-24" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {rules.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-[var(--muted-foreground)] py-8">
                  No recurring rules yet.
                </TableCell>
              </TableRow>
            )}
            {rules.map((rule) => (
              <TableRow key={rule.id}>
                <TableCell className="font-medium">
                  {customerMap.get(rule.customerId) ?? "Unknown Customer"}
                </TableCell>
                <TableCell>
                  <Badge variant="info">{rule.interval}</Badge>
                </TableCell>
                <TableCell className="text-[var(--muted-foreground)]">
                  {formatDate(rule.nextRunAt)}
                </TableCell>
                <TableCell className="text-[var(--muted-foreground)]">
                  {rule.endAt ? formatDate(rule.endAt) : "No end"}
                </TableCell>
                <TableCell>
                  <Badge variant={rule.active ? "success" : "secondary"}>
                    {rule.active ? "Active" : "Paused"}
                  </Badge>
                </TableCell>
                <TableCell className="text-right">
                  <Button variant="outline" size="sm" asChild>
                    <Link href={`/${orgSlug}/invoices/recurring/${rule.id}/edit`}>
                      <Pencil className="h-3.5 w-3.5 mr-1" />
                      Edit template
                    </Link>
                  </Button>
                </TableCell>
                <TableCell>
                  <RecurringRuleActions
                    ruleId={rule.id}
                    active={rule.active}
                    onToggle={async () => {
                      "use server";
                      return updateRecurringRule(orgSlug, rule.id, !rule.active);
                    }}
                    onDelete={async () => {
                      "use server";
                      return deleteRecurringRule(orgSlug, rule.id);
                    }}
                  />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
