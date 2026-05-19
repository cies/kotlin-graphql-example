import { auth } from "@/lib/auth/auth";
import { prisma } from "./prisma";

export async function getOrgContext(orgSlug: string) {
  const session = await auth();
  if (!session?.user) return null;

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true, name: true, slug: true },
  });

  if (!org) return null;

  if (session.user.userType === "STAFF") {
    const membership = await prisma.organizationMember.findUnique({
      where: {
        organizationId_userId: {
          organizationId: org.id,
          userId: session.user.id,
        },
      },
    });
    if (!membership) return null;
    return { org, membership, session };
  }

  return { org, membership: null, session };
}

export function withOrgId<T extends { organizationId?: string }>(
  data: T,
  organizationId: string
): T & { organizationId: string } {
  return { ...data, organizationId };
}
