"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { StaffRole } from "@prisma/client";
import { randomBytes } from "crypto";
import { hash } from "bcryptjs";
import { sendMail } from "@/lib/email/mailer";
import { composeEmail } from "@/lib/email/compose";
import { logAudit } from "@/lib/audit/log";
import { resolveAbsoluteAppUrl } from "@/lib/env/resolve-public-app-url.server";

async function getOrgId(orgSlug: string): Promise<{ orgId: string; userId: string } | null> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") return null;

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) return null;

  const member = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: { organizationId: org.id, userId: session.user.id },
    },
  });

  return member ? { orgId: org.id, userId: session.user.id } : null;
}

async function requireAdminOrOwner(orgSlug: string) {
  const ctx = await getOrgId(orgSlug);
  if (!ctx) return null;

  const member = await prisma.organizationMember.findUnique({
    where: { organizationId_userId: { organizationId: ctx.orgId, userId: ctx.userId } },
    select: { role: true },
  });

  if (!member || (member.role !== "OWNER" && member.role !== "ADMIN")) return null;
  return ctx;
}

// ─── Invite ────────────────────────────────────────────────────────────────────

const inviteSchema = z.object({
  email: z.string().email(),
  role: z.nativeEnum(StaffRole).default("STAFF"),
});

export async function inviteMember(
  orgSlug: string,
  input: { email: string; role: StaffRole }
) {
  const ctx = await requireAdminOrOwner(orgSlug);
  if (!ctx) return { error: "Unauthorized" };

  const parsed = inviteSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const { email, role } = parsed.data;

  // Check if already a member
  const existingUser = await prisma.user.findUnique({ where: { email }, select: { id: true } });
  if (existingUser) {
    const existingMember = await prisma.organizationMember.findUnique({
      where: { organizationId_userId: { organizationId: ctx.orgId, userId: existingUser.id } },
    });
    if (existingMember) return { error: "This user is already a member of this organization." };
  }

  // Invalidate any pending invites for same email+org
  await prisma.memberInvite.deleteMany({
    where: { organizationId: ctx.orgId, email, acceptedAt: null },
  });

  const token = randomBytes(32).toString("hex");
  const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000); // 7 days

  await prisma.memberInvite.create({
    data: { organizationId: ctx.orgId, email, role, token, expiresAt },
  });

  const inviterSession = await auth();
  const inviterName = inviterSession?.user?.name || inviterSession?.user?.email || "A team member";
  const org = await prisma.organization.findUnique({ where: { id: ctx.orgId }, select: { name: true } });
  const acceptUrl = await resolveAbsoluteAppUrl(`/auth/invite/${token}`);

  try {
    const composed = await composeEmail(ctx.orgId, "member.invite", {
      "inviter.name": inviterName,
      "org.name": org?.name ?? orgSlug,
      "invite.role": role,
      "invite.acceptUrl": acceptUrl,
      "invite.expiresAt": expiresAt.toLocaleDateString("en-GB"),
    });
    await sendMail({ orgId: ctx.orgId, to: email, subject: composed.subject, html: composed.html });
  } catch {
    // Email failure must not block invite creation
  }

  await logAudit({
    organizationId: ctx.orgId,
    action: "CREATE",
    entityType: "MEMBER_INVITE",
    entityId: email,
    metadata: { email, role },
  }).catch(() => {});

  revalidatePath(`/${orgSlug}/team`);
  return { success: true };
}

// ─── Update member ─────────────────────────────────────────────────────────────

const updateMemberSchema = z.object({
  role: z.nativeEnum(StaffRole).optional(),
  hourlyRate: z.string().optional(),
  isRetainer: z.boolean().optional(),
  retainerHours: z.string().optional(),
});

export async function updateMember(
  orgSlug: string,
  memberId: string,
  input: {
    role?: StaffRole;
    hourlyRate?: string;
    isRetainer?: boolean;
    retainerHours?: string;
  }
) {
  const ctx = await requireAdminOrOwner(orgSlug);
  if (!ctx) return { error: "Unauthorized" };

  const parsed = updateMemberSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const { role, hourlyRate, isRetainer, retainerHours } = parsed.data;

  const member = await prisma.organizationMember.findFirst({
    where: { id: memberId, organizationId: ctx.orgId },
  });
  if (!member) return { error: "Member not found" };

  await prisma.organizationMember.update({
    where: { id: memberId },
    data: {
      ...(role !== undefined && { role }),
      ...(hourlyRate !== undefined && { hourlyRate: hourlyRate || null }),
      ...(isRetainer !== undefined && { isRetainer }),
      ...(retainerHours !== undefined && { retainerHours: retainerHours || null }),
    },
  });

  await logAudit({
    organizationId: ctx.orgId,
    action: "UPDATE",
    entityType: "ORGANIZATION_MEMBER",
    entityId: memberId,
    metadata: { role, isRetainer },
  }).catch(() => {});

  revalidatePath(`/${orgSlug}/team`);
  return { success: true };
}

// ─── Remove member ─────────────────────────────────────────────────────────────

export async function removeMember(orgSlug: string, memberId: string) {
  const ctx = await requireAdminOrOwner(orgSlug);
  if (!ctx) return { error: "Unauthorized" };

  const member = await prisma.organizationMember.findFirst({
    where: { id: memberId, organizationId: ctx.orgId },
    include: { user: { select: { email: true } } },
  });
  if (!member) return { error: "Member not found" };

  // Cannot remove the last OWNER
  if (member.role === "OWNER") {
    const ownerCount = await prisma.organizationMember.count({
      where: { organizationId: ctx.orgId, role: "OWNER" },
    });
    if (ownerCount <= 1) return { error: "Cannot remove the last owner." };
  }

  await prisma.organizationMember.delete({ where: { id: memberId } });

  await logAudit({
    organizationId: ctx.orgId,
    action: "DELETE",
    entityType: "ORGANIZATION_MEMBER",
    entityId: memberId,
    metadata: { email: member.user.email },
  }).catch(() => {});

  revalidatePath(`/${orgSlug}/team`);
  return { success: true };
}

// ─── Revoke invite ─────────────────────────────────────────────────────────────

export async function revokeInvite(orgSlug: string, inviteId: string) {
  const ctx = await requireAdminOrOwner(orgSlug);
  if (!ctx) return { error: "Unauthorized" };

  await prisma.memberInvite.deleteMany({
    where: { id: inviteId, organizationId: ctx.orgId },
  });

  revalidatePath(`/${orgSlug}/team`);
  return { success: true };
}

// ─── Accept invite (public) ────────────────────────────────────────────────────

const acceptSchema = z.object({
  name: z.string().min(1),
  password: z.string().min(8),
});

export async function acceptInvite(
  token: string,
  input: { name: string; password: string }
) {
  const parsed = acceptSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const invite = await prisma.memberInvite.findUnique({
    where: { token },
    include: { organization: { select: { id: true, slug: true, name: true } } },
  });

  if (!invite) return { error: "Invalid or expired invitation." };
  if (invite.acceptedAt) return { error: "This invitation has already been used." };
  if (invite.expiresAt < new Date()) return { error: "This invitation has expired." };

  const passwordHash = await hash(input.password, 12);

  // Upsert user - create if new, otherwise link existing
  let user = await prisma.user.findUnique({ where: { email: invite.email } });

  if (!user) {
    user = await prisma.user.create({
      data: {
        email: invite.email,
        name: input.name,
        passwordHash,
        userType: "STAFF",
      },
    });
  } else {
    // Update name/password only if not already set
    await prisma.user.update({
      where: { id: user.id },
      data: {
        ...(user.name ? {} : { name: input.name }),
        ...(user.passwordHash ? {} : { passwordHash }),
      },
    });
  }

  // Check not already a member
  const existing = await prisma.organizationMember.findUnique({
    where: { organizationId_userId: { organizationId: invite.organizationId, userId: user.id } },
  });

  if (!existing) {
    await prisma.organizationMember.create({
      data: {
        organizationId: invite.organizationId,
        userId: user.id,
        role: invite.role,
      },
    });
  }

  await prisma.memberInvite.update({
    where: { token },
    data: { acceptedAt: new Date() },
  });

  await logAudit({
    organizationId: invite.organizationId,
    action: "CREATE",
    entityType: "ORGANIZATION_MEMBER",
    entityId: user.id,
    metadata: { email: invite.email, role: invite.role, via: "invite" },
  }).catch(() => {});

  return { success: true, orgSlug: invite.organization.slug };
}
