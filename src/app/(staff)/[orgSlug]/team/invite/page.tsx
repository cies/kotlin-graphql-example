import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { InviteForm } from "@/components/team/invite-form";
import { ArrowLeft } from "lucide-react";

interface Props {
  params: Promise<{ orgSlug: string }>;
}

export default async function InviteMemberPage({ params }: Props) {
  const { orgSlug } = await params;
  const session = await auth();
  if (!session?.user) redirect("/auth/login");

  const org = await prisma.organization.findUnique({ where: { slug: orgSlug } });
  if (!org) redirect("/auth/login");

  return (
    <div>
      <div className="flex items-center gap-4 mb-6">
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/${orgSlug}/team`}>
            <ArrowLeft className="h-4 w-4" />
          </Link>
        </Button>
        <div>
          <h1 className="text-2xl font-bold">Invite team member</h1>
          <p className="text-[var(--muted-foreground)] text-sm mt-0.5">
            An invitation link will be sent to their email address
          </p>
        </div>
      </div>

      <Card className="max-w-lg">
        <CardHeader>
          <CardTitle>New invitation</CardTitle>
        </CardHeader>
        <CardContent>
          <InviteForm orgSlug={orgSlug} />
        </CardContent>
      </Card>
    </div>
  );
}
