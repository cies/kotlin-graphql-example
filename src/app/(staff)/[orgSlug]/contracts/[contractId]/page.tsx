import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { notFound, redirect } from "next/navigation";
import { ContractForm } from "@/components/contracts/contract-form";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { sendContractForSignature } from "@/lib/actions/contracts";
import { formatDateTime } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string; contractId: string }>;
}

export default async function ContractDetailPage({ params }: Props) {
  const { orgSlug, contractId } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) redirect("/auth/login");

  const [customers, contract] = await Promise.all([
    prisma.customer.findMany({
      where: { organizationId: org.id },
      select: { id: true, companyName: true, firstName: true, lastName: true },
    }),
    prisma.contract.findUnique({
      where: { id: contractId, organizationId: org.id },
      include: { signatures: { orderBy: { signedAt: "desc" }, take: 1 } },
    }),
  ]);

  if (!contract) notFound();

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{contract.title}</h1>
          <p className="text-sm text-[var(--muted-foreground)] mt-1">
            Updated {formatDateTime(contract.updatedAt)}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant={contract.status === "SIGNED" ? "success" : contract.status === "SENT" ? "info" : "secondary"}>
            {contract.status}
          </Badge>
          <Button variant="outline" asChild>
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
              <Button>Send for signature</Button>
            </form>
          )}
        </div>
      </div>

      {contract.signatures[0] && (
        <div className="rounded-md border border-[var(--border)] p-3 text-sm">
          Signed by <strong>{contract.signatures[0].signerName}</strong> ({contract.signatures[0].signerEmail}) on{" "}
          {formatDateTime(contract.signatures[0].signedAt)}
        </div>
      )}

      <ContractForm
        orgSlug={orgSlug}
        customers={customers.map((c) => ({
          id: c.id,
          name:
            c.companyName ||
            `${c.firstName || ""} ${c.lastName || ""}`.trim() ||
            "Customer",
        }))}
        initial={{
          id: contract.id,
          customerId: contract.customerId,
          title: contract.title,
          bodyHtml: contract.bodyHtml,
        }}
      />
    </div>
  );
}
