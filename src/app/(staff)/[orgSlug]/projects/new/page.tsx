import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { ProjectForm } from "@/components/projects/project-form";

interface Props {
  params: Promise<{ orgSlug: string }>;
  searchParams: Promise<{ customerId?: string }>;
}

export const metadata = { title: "New Project" };

export default async function NewProjectPage({ params, searchParams }: Props) {
  const { orgSlug } = await params;
  const { customerId } = await searchParams;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    include: { settings: true },
  });
  if (!org) redirect("/auth/login");

  const customers = await prisma.customer.findMany({
    where: { organizationId: org.id },
    select: {
      id: true,
      type: true,
      companyName: true,
      firstName: true,
      lastName: true,
    },
    orderBy: { createdAt: "desc" },
  });

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold">New Project</h1>
      </div>
      <ProjectForm
        orgSlug={orgSlug}
        customers={customers}
        defaultCustomerId={customerId}
        orgSemiMonthlyDefaults={org.settings ? {
          splitDay: org.settings.semiMonthlyPeriodSplitDay,
          emitDay1: org.settings.semiMonthlyEmitDay1,
          emitDay2: org.settings.semiMonthlyEmitDay2,
        } : undefined}
      />
    </div>
  );
}
