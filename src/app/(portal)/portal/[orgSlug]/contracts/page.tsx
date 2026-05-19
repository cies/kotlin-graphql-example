import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatDateTime } from "@/lib/utils/format";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function PortalContractsPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect(`/portal/${orgSlug}/login`);

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug }, select: { id: true } });
  if (!org) redirect("/auth/login");

  const contact = await prisma.customerContact.findFirst({
    where: { organizationId: org.id, userId: session.user.id },
    select: { customerId: true, canSeeContracts: true },
  });
  if (!contact || !contact.canSeeContracts) redirect(`/portal/${orgSlug}`);

  const contracts = await prisma.contract.findMany({
    where: { organizationId: org.id, customerId: contact.customerId },
    include: { signatures: { orderBy: { signedAt: "desc" }, take: 1 } },
    orderBy: { updatedAt: "desc" },
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Contracts</h1>
      <Card>
        <CardHeader>
          <CardTitle>Documents</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {contracts.length === 0 && <p className="text-sm text-[var(--muted-foreground)]">No contracts available.</p>}
          {contracts.map((contract) => (
            <div key={contract.id} className="rounded-md border border-[var(--border)] p-3 flex items-center justify-between gap-3">
              <div>
                <p className="font-medium">{contract.title}</p>
                <p className="text-sm text-[var(--muted-foreground)]">
                  Updated {formatDateTime(contract.updatedAt)}
                </p>
                {contract.signatures[0] && (
                  <p className="text-xs text-[var(--muted-foreground)]">
                    Signed by {contract.signatures[0].signerName} on {formatDateTime(contract.signatures[0].signedAt)}
                  </p>
                )}
              </div>
              <div className="flex items-center gap-2">
                <Badge variant={contract.status === "SIGNED" ? "success" : contract.status === "SENT" ? "info" : "secondary"}>
                  {contract.status}
                </Badge>
                <a className="text-sm underline" href={`/api/pdf/contract/${contract.id}`} target="_blank" rel="noreferrer">
                  PDF
                </a>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
