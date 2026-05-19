import { prisma } from "@/lib/db/prisma";
import { AcceptInviteForm } from "./accept-invite-form";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Building2, AlertCircle } from "lucide-react";

interface Props {
  params: Promise<{ token: string }>;
}

export default async function AcceptInvitePage({ params }: Props) {
  const { token } = await params;

  const invite = await prisma.memberInvite.findUnique({
    where: { token },
    include: { organization: { select: { name: true, slug: true } } },
  });

  const isInvalid = !invite;
  const isUsed = !!invite?.acceptedAt;
  const isExpired = invite && invite.expiresAt < new Date();

  if (isInvalid || isUsed || isExpired) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[var(--muted)] px-4">
        <div className="w-full max-w-sm">
          <div className="flex justify-center mb-6">
            <div className="flex items-center gap-2">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-[var(--primary)] text-white">
                <Building2 className="h-5 w-5" />
              </div>
              <span className="text-xl font-bold">CRM</span>
            </div>
          </div>
          <Card>
            <CardHeader>
              <div className="flex items-center gap-2 text-[var(--destructive)]">
                <AlertCircle className="h-5 w-5" />
                <CardTitle>Invalid invitation</CardTitle>
              </div>
              <CardDescription>
                {isUsed
                  ? "This invitation has already been accepted."
                  : isExpired
                  ? "This invitation has expired. Ask an admin to send a new one."
                  : "This invitation link is invalid or does not exist."}
              </CardDescription>
            </CardHeader>
          </Card>
        </div>
      </div>
    );
  }

  const existingUser = await prisma.user.findUnique({
    where: { email: invite.email },
    select: { id: true, name: true },
  });

  return (
    <div className="flex min-h-screen items-center justify-center bg-[var(--muted)] px-4">
      <div className="w-full max-w-sm">
        <div className="flex justify-center mb-6">
          <div className="flex items-center gap-2">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-[var(--primary)] text-white">
              <Building2 className="h-5 w-5" />
            </div>
            <span className="text-xl font-bold">CRM</span>
          </div>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>Join {invite.organization.name}</CardTitle>
            <CardDescription>
              You&apos;ve been invited to join as{" "}
              <strong>{invite.role.replace("_", " ")}</strong>.
              {existingUser
                ? " Your existing account will be linked."
                : " Create a password to get started."}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <AcceptInviteForm
              token={token}
              email={invite.email}
              orgSlug={invite.organization.slug}
              isExistingUser={!!existingUser}
              existingName={existingUser?.name ?? null}
            />
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
