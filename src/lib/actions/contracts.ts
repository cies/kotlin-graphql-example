"use server";

import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { revalidatePath } from "next/cache";
import { createHash, randomBytes } from "crypto";
import { z } from "zod";
import { sendMail, type MailAttachment } from "@/lib/email/mailer";
import { composeEmail } from "@/lib/email/compose";
import { generateContractPdf, type ContractPdfData } from "@/lib/pdf/contract-pdf";
import { headers } from "next/headers";
import { logAudit } from "@/lib/audit/log";
import { sanitizeContractBodyHtml } from "@/lib/html/sanitize-contract-body";
import { resolveAbsoluteAppUrl } from "@/lib/env/resolve-public-app-url.server";
import { resolveContractNotificationEmails } from "@/lib/customers/notification-email-resolve";

const contractSchema = z.object({
  customerId: z.string().min(1),
  title: z.string().min(2),
  bodyHtml: z.string().min(10),
});

export type ContractInput = z.infer<typeof contractSchema>;

function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

async function getStaffOrgId(orgSlug: string): Promise<string | null> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") return null;

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });
  if (!org) return null;

  const member = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: {
        organizationId: org.id,
        userId: session.user.id,
      },
    },
    select: { id: true },
  });
  return member ? org.id : null;
}

function applyMergeTags(template: string, vars: Record<string, string>): string {
  let result = template;
  for (const [key, value] of Object.entries(vars)) {
    const pattern = new RegExp(`\\{\\{${key.replace(/\./g, "\\.")}\\}\\}`, "g");
    result = result.replace(pattern, value ?? "");
  }
  return result.replace(/\{\{[^}]+\}\}/g, "");
}

export async function createContract(orgSlug: string, input: ContractInput) {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = contractSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const contract = await prisma.contract.create({
    data: {
      organizationId: orgId,
      customerId: parsed.data.customerId,
      title: parsed.data.title,
      bodyHtml: sanitizeContractBodyHtml(parsed.data.bodyHtml),
    },
  });

  await logAudit({
    organizationId: orgId,
    action: "CREATE",
    entityType: "CONTRACT",
    entityId: contract.id,
    metadata: { title: contract.title, customerId: parsed.data.customerId },
  });

  revalidatePath(`/${orgSlug}/contracts`);
  return { success: true, contractId: contract.id };
}

export async function updateContract(orgSlug: string, contractId: string, input: ContractInput) {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = contractSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  await prisma.contract.update({
    where: { id: contractId, organizationId: orgId },
    data: {
      customerId: parsed.data.customerId,
      title: parsed.data.title,
      bodyHtml: sanitizeContractBodyHtml(parsed.data.bodyHtml),
    },
  });

  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "CONTRACT",
    entityId: contractId,
    metadata: { title: parsed.data.title },
  });

  revalidatePath(`/${orgSlug}/contracts`);
  revalidatePath(`/${orgSlug}/contracts/${contractId}`);
  return { success: true };
}

export async function sendContractForSignature(orgSlug: string, contractId: string) {
  const orgId = await getStaffOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const contract = await prisma.contract.findUnique({
    where: { id: contractId, organizationId: orgId },
    include: {
      customer: {
        include: {
          notificationEmails: true,
          contacts: {
            where: { isPrimary: true },
            include: { user: true },
            take: 1,
          },
        },
      },
      organization: { include: { settings: true } },
    },
  });
  if (!contract) return { error: "Contract not found" };

  const contractTo = resolveContractNotificationEmails(contract.customer);
  const targetEmail = contractTo[0];
  if (!targetEmail) return { error: "Customer does not have an email recipient" };

  const token = randomBytes(32).toString("hex");
  const tokenHash = hashToken(token);
  const expiresAt = new Date(Date.now() + 1000 * 60 * 60 * 24 * 7);
  const signUrl = await resolveAbsoluteAppUrl(`/sign/${token}`);

  await prisma.contract.update({
    where: { id: contract.id },
    data: {
      status: "SENT",
      signTokenHash: tokenHash,
      signTokenExpiresAt: expiresAt,
    },
  });

  const customerName =
    contract.customer.companyName ||
    `${contract.customer.firstName || ""} ${contract.customer.lastName || ""}`.trim() ||
    "Customer";

  const signInnerHtml = `<p>Dear ${customerName},</p>
<p>Please review and sign the contract <strong>${contract.title}</strong>.</p>
<p style="margin-top:24px">
  <a href="${signUrl}" style="display:inline-block;padding:12px 24px;background:#2563eb;color:#ffffff;text-decoration:none;border-radius:6px;font-weight:600;font-size:14px">Open Contract &amp; Sign</a>
</p>
<p style="color:#64748b;font-size:13px">This link expires on ${expiresAt.toLocaleDateString("en-GB")}. If you have any questions, please contact us.</p>`;

  const { html: composedHtml, attachments: signAttachments } = await composeEmail(
    orgId,
    "contract.signed",
    {
      "contract.title": contract.title,
      "signer.name": customerName,
      "signer.email": targetEmail,
      "signer.signedAt": new Date().toLocaleString("en-GB"),
      "org.name": contract.organization.settings?.companyName ?? contract.organization.name,
    }
  ).catch(() => ({ html: signInnerHtml, attachments: [] as MailAttachment[] }));

  const signSubject = `Signature requested: ${contract.title}`;
  const result = await sendMail({
    orgId,
    to: contractTo,
    subject: signSubject,
    html: composedHtml,
    attachments: signAttachments,
  });
  if (!result.ok) return { error: result.error ?? "Failed to send contract email" };

  await logAudit({
    organizationId: orgId,
    action: "SEND",
    entityType: "CONTRACT",
    entityId: contractId,
    metadata: { to: contractTo.join(", "), expiresAt: expiresAt.toISOString() },
  });

  revalidatePath(`/${orgSlug}/contracts`);
  revalidatePath(`/${orgSlug}/contracts/${contractId}`);
  return { success: true };
}

const signContractSchema = z.object({
  token: z.string().min(20),
  signerName: z.string().min(2),
  signerEmail: z.string().email(),
  signatureSvg: z.string().min(10),
  ipAddress: z.string().optional(),
  userAgent: z.string().optional(),
});

export async function signContractByToken(input: z.infer<typeof signContractSchema>) {
  const requestHeaders = await headers();
  const inferredIp = requestHeaders.get("x-forwarded-for")?.split(",")[0]?.trim() || undefined;
  const inferredUa = requestHeaders.get("user-agent") || undefined;

  const parsed = signContractSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const tokenHash = hashToken(parsed.data.token);
  const contract = await prisma.contract.findUnique({
    where: { signTokenHash: tokenHash },
    include: {
      customer: {
        include: {
          contacts: { include: { user: true } },
        },
      },
      organization: { include: { settings: true } },
    },
  });
  if (!contract) return { error: "Invalid or expired link" };
  if (!contract.signTokenExpiresAt || contract.signTokenExpiresAt < new Date()) {
    return { error: "This signature link has expired" };
  }
  if (contract.status === "SIGNED") return { error: "Contract is already signed" };

  const signedAt = new Date();
  const signature = await prisma.contractSignature.create({
    data: {
      contractId: contract.id,
      signerName: parsed.data.signerName,
      signerEmail: parsed.data.signerEmail,
      signatureSvg: parsed.data.signatureSvg,
      ipAddress: parsed.data.ipAddress || inferredIp,
      userAgent: parsed.data.userAgent || inferredUa,
      signedAt,
    },
  });

  await prisma.contract.update({
    where: { id: contract.id },
    data: {
      status: "SIGNED",
      signTokenHash: null,
      signTokenExpiresAt: null,
    },
  });

  await logAudit({
    organizationId: contract.organizationId,
    action: "SIGN",
    entityType: "CONTRACT",
    entityId: contract.id,
    actorEmail: parsed.data.signerEmail,
    actorType: "EXTERNAL_SIGNER",
    metadata: {
      signatureId: signature.id,
      signerName: parsed.data.signerName,
      ipAddress: parsed.data.ipAddress || inferredIp,
      userAgent: parsed.data.userAgent || inferredUa,
    },
  });

  const pdfData = await buildContractPdfData(contract.id);
  if (!pdfData) return { error: "Failed to build contract PDF data" };

  const pdfBuffer = await generateContractPdf(pdfData);
  const orgName = contract.organization.settings?.companyName || contract.organization.name;
  const targetEmails = new Set<string>();
  if (contract.customer.email) targetEmails.add(contract.customer.email);
  for (const c of contract.customer.contacts) {
    if (c.user.email) targetEmails.add(c.user.email);
  }

  const { subject, html, attachments: emailAttachments } = await composeEmail(
    contract.organizationId,
    "contract.signed",
    {
      "contract.title": contract.title,
      "signer.name": parsed.data.signerName,
      "signer.email": parsed.data.signerEmail,
      "signer.signedAt": signedAt.toLocaleString("en-GB"),
      "org.name": orgName,
    },
    [
      {
        filename: `contract-${contract.title.replace(/\s+/g, "-").toLowerCase()}.pdf`,
        content: pdfBuffer,
        contentType: "application/pdf",
      },
    ]
  );

  for (const email of targetEmails) {
    await sendMail({
      orgId: contract.organizationId,
      to: email,
      subject,
      html,
      attachments: emailAttachments,
    });
  }

  return { success: true };
}

export async function getContractByToken(token: string) {
  const tokenHash = hashToken(token);
  const contract = await prisma.contract.findUnique({
    where: { signTokenHash: tokenHash },
    include: {
      customer: true,
      organization: { select: { name: true } },
    },
  });
  if (!contract) return null;
  if (!contract.signTokenExpiresAt || contract.signTokenExpiresAt < new Date()) return null;

  const customerName =
    contract.customer.companyName ||
    `${contract.customer.firstName || ""} ${contract.customer.lastName || ""}`.trim() ||
    "Customer";

  const mergedHtml = applyMergeTags(contract.bodyHtml, {
    "customer.companyName": contract.customer.companyName || "",
    "customer.firstName": contract.customer.firstName || "",
    "customer.lastName": contract.customer.lastName || "",
    "customer.email": contract.customer.email || "",
    "org.name": contract.organization.name,
    "contract.title": contract.title,
  });

  return {
    id: contract.id,
    title: contract.title,
    bodyHtml: sanitizeContractBodyHtml(mergedHtml),
    customerName,
    customerEmail: contract.customer.email || "",
  };
}

export async function buildContractPdfData(contractId: string): Promise<ContractPdfData | null> {
  const contract = await prisma.contract.findUnique({
    where: { id: contractId },
    include: {
      customer: true,
      signatures: { orderBy: { signedAt: "desc" }, take: 1 },
      organization: { include: { settings: true } },
    },
  });
  if (!contract) return null;

  const customerName =
    contract.customer.companyName ||
    `${contract.customer.firstName || ""} ${contract.customer.lastName || ""}`.trim() ||
    contract.customer.email ||
    "Customer";

  return {
    contract: {
      title: contract.title,
      bodyHtml: contract.bodyHtml,
      status: contract.status,
      updatedAt: contract.updatedAt,
    },
    organization: {
      name: contract.organization.settings?.companyName || contract.organization.name,
    },
    customer: { name: customerName, email: contract.customer.email || undefined },
    signature: contract.signatures[0]
      ? {
          signerName: contract.signatures[0].signerName,
          signerEmail: contract.signatures[0].signerEmail,
          signatureSvg: contract.signatures[0].signatureSvg,
          ipAddress: contract.signatures[0].ipAddress || undefined,
          userAgent: contract.signatures[0].userAgent || undefined,
          signedAt: contract.signatures[0].signedAt,
        }
      : undefined,
  };
}
