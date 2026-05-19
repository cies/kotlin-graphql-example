import nodemailer from "nodemailer";
import type SMTPTransport from "nodemailer/lib/smtp-transport";
import type { SmtpEncryption } from "@prisma/client";
import { prisma } from "@/lib/db/prisma";

export interface OrgSmtp {
  host: string;
  port: number;
  encryption: SmtpEncryption;
  user: string;
  pass: string;
  from: string;
}

export async function getOrgSmtp(orgId: string): Promise<OrgSmtp | null> {
  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: {
      smtpHost: true,
      smtpPort: true,
      smtpEncryption: true,
      smtpUser: true,
      smtpPass: true,
      smtpFrom: true,
    },
  });

  if (
    !settings?.smtpHost ||
    !settings.smtpPort ||
    !settings.smtpUser ||
    !settings.smtpPass ||
    !settings.smtpFrom
  ) {
    return null;
  }

  return {
    host: settings.smtpHost,
    port: settings.smtpPort,
    encryption: settings.smtpEncryption,
    user: settings.smtpUser,
    pass: settings.smtpPass,
    from: settings.smtpFrom,
  };
}

function transportOptions(smtp: OrgSmtp): SMTPTransport.Options {
  const base: SMTPTransport.Options = {
    host: smtp.host,
    port: smtp.port,
    auth: { user: smtp.user, pass: smtp.pass },
  };
  switch (smtp.encryption) {
    case "SSL":
      return { ...base, secure: true };
    case "TLS":
      return {
        ...base,
        secure: false,
        requireTLS: true,
        tls: { rejectUnauthorized: true },
      };
    case "NONE":
      return {
        ...base,
        secure: false,
        ignoreTLS: true,
        requireTLS: false,
      };
  }
}

export function createTransporter(smtp: OrgSmtp) {
  return nodemailer.createTransport(transportOptions(smtp));
}

export interface MailAttachment {
  filename: string;
  content: Buffer;
  contentType: string;
  /** Nodemailer CID for inline images (e.g. "logo@crm") */
  cid?: string;
}

export interface SendMailOptions {
  orgId: string;
  to: string | string[];
  /** Optional blind copy (one or many comma-separated-ready strings). */
  bcc?: string | string[];
  subject: string;
  html: string;
  attachments?: MailAttachment[];
}

export async function sendMail(opts: SendMailOptions): Promise<{ ok: boolean; error?: string }> {
  const smtp = await getOrgSmtp(opts.orgId);
  if (!smtp) return { ok: false, error: "SMTP not configured for this organization." };

  try {
    const transporter = createTransporter(smtp);
    await transporter.sendMail({
      from: smtp.from,
      to: Array.isArray(opts.to) ? opts.to.join(", ") : opts.to,
      ...(opts.bcc
        ? {
            bcc: Array.isArray(opts.bcc) ? opts.bcc.join(", ") : opts.bcc,
          }
        : {}),
      subject: opts.subject,
      html: opts.html,
      attachments: opts.attachments?.map((a) => ({
        filename: a.filename,
        content: a.content,
        contentType: a.contentType,
        ...(a.cid ? { cid: a.cid } : {}),
      })),
    });
    return { ok: true };
  } catch (err) {
    console.error("[Mailer] Send failed:", err);
    return { ok: false, error: String(err) };
  }
}
