import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { notFound, redirect } from "next/navigation";
import { ProjectForm } from "@/components/projects/project-form";

interface Props {
  params: Promise<{ orgSlug: string; projectId: string }>;
}

export const metadata = { title: "Edit Project" };

export default async function EditProjectPage({ params }: Props) {
  const { orgSlug, projectId } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    include: { settings: true },
  });
  if (!org) redirect("/auth/login");

  const [project, customers] = await Promise.all([
    prisma.project.findUnique({
      where: { id: projectId, organizationId: org.id },
    }),
    prisma.customer.findMany({
      where: { organizationId: org.id },
      select: { id: true, type: true, companyName: true, firstName: true, lastName: true },
    }),
  ]);

  if (!project) notFound();

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">Edit Project</h1>
      </div>
      <ProjectForm
        orgSlug={orgSlug}
        projectId={projectId}
        customers={customers}
        orgSemiMonthlyDefaults={org.settings ? {
          splitDay: org.settings.semiMonthlyPeriodSplitDay,
          emitDay1: org.settings.semiMonthlyEmitDay1,
          emitDay2: org.settings.semiMonthlyEmitDay2,
        } : undefined}
        defaultValues={{
          customerId: project.customerId,
          name: project.name,
          description: project.description ?? "",
          billingMode: project.billingMode,
          hourlyRate: project.hourlyRate?.toString() ?? "",
          fixedFee: project.fixedFee?.toString() ?? "",
          currency: project.currency,
          autoInvoice: project.autoInvoice,
          autoInvoiceDay: project.autoInvoiceDay ?? 1,
          autoInvoiceCycle: project.autoInvoiceCycle,
          semiMonthlyPeriodSplitDay: project.semiMonthlyPeriodSplitDay,
          semiMonthlyEmitDay1: project.semiMonthlyEmitDay1,
          semiMonthlyEmitDay2: project.semiMonthlyEmitDay2,
          statusEmailCycle: project.statusEmailCycle,
          startDate: project.startDate?.toISOString().split("T")[0] ?? "",
          endDate: project.endDate?.toISOString().split("T")[0] ?? "",
        }}
      />
    </div>
  );
}
