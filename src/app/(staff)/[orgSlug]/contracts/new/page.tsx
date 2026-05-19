import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { ContractForm } from "@/components/contracts/contract-form";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function NewContractPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) redirect("/auth/login");

  const customers = await prisma.customer.findMany({
    where: { organizationId: org.id },
    select: { id: true, companyName: true, firstName: true, lastName: true },
    orderBy: { createdAt: "desc" },
  });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">New contract</h1>
      </div>
      <ContractForm
        orgSlug={orgSlug}
        customers={customers.map((c) => ({
          id: c.id,
          name:
            c.companyName ||
            `${c.firstName || ""} ${c.lastName || ""}`.trim() ||
            "Customer",
        }))}
      />
    </div>
  );
}
