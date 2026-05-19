import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Users, FolderKanban, FileText, CreditCard } from "lucide-react";
import { formatCurrency, normalizeNumberFormatStyle } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function DashboardPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();

  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    include: { settings: true },
  });

  if (!org) redirect("/auth/login");

  const [customerCount, projectCount, invoiceStats] = await Promise.all([
    prisma.customer.count({ where: { organizationId: org.id } }),
    prisma.project.count({ where: { organizationId: org.id } }),
    prisma.invoice.aggregate({
      where: { organizationId: org.id, status: { in: ["SENT", "PARTIAL", "OVERDUE"] } },
      _sum: { total: true, amountPaid: true },
      _count: true,
    }),
  ]);

  const outstanding =
    Number(invoiceStats._sum.total ?? 0) - Number(invoiceStats._sum.amountPaid ?? 0);

  const currency = org.settings?.displayCurrency ?? "EUR";
  const numberFormatStyle = normalizeNumberFormatStyle(
    (org.settings as unknown as { numberFormatStyle?: string | null } | null)?.numberFormatStyle
  );

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">Dashboard</h1>
        <p className="text-[var(--muted-foreground)] mt-1">Welcome back to {org.name}</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Customers</CardTitle>
            <Users className="h-4 w-4 text-[var(--muted-foreground)]" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{customerCount}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Projects</CardTitle>
            <FolderKanban className="h-4 w-4 text-[var(--muted-foreground)]" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{projectCount}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Open Invoices</CardTitle>
            <FileText className="h-4 w-4 text-[var(--muted-foreground)]" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{invoiceStats._count}</div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Outstanding</CardTitle>
            <CreditCard className="h-4 w-4 text-[var(--muted-foreground)]" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {formatCurrency(outstanding, currency, numberFormatStyle)}
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
