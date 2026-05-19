import { notFound } from "next/navigation";
import { getContractByToken } from "@/lib/actions/contracts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { SignContractForm } from "@/components/contracts/sign-contract-form";

interface Props {
  params: Promise<{ token: string }>;
}

export default async function PublicSignPage({ params }: Props) {
  const { token } = await params;
  const contract = await getContractByToken(token);
  if (!contract) notFound();

  return (
    <div className="min-h-screen bg-[var(--muted)] p-6">
      <div className="mx-auto max-w-4xl space-y-6">
        <Card>
          <CardHeader>
            <CardTitle>{contract.title}</CardTitle>
          </CardHeader>
          <CardContent>
            <div
              className="prose max-w-none text-sm"
              dangerouslySetInnerHTML={{ __html: contract.bodyHtml }}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Electronic signature</CardTitle>
          </CardHeader>
          <CardContent>
            <SignContractForm token={token} defaultEmail={contract.customerEmail} />
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
