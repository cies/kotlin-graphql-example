import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { headers } from "next/headers";

export type AuditEntity =
  | "ORGANIZATION"
  | "USER"
  | "CUSTOMER"
  | "CUSTOMER_CONTACT"
  | "CUSTOMER_NOTE"
  | "PROJECT"
  | "TASK"
  | "TIME_ENTRY"
  | "INVOICE"
  | "INVOICE_LINE"
  | "INVOICE_NOTE"
  | "INVOICE_ATTACHMENT"
  | "RECURRING_RULE"
  | "PAYMENT"
  | "CONTRACT"
  | "CONTRACT_SIGNATURE"
  | "REMINDER"
  | "SUBSCRIPTION"
  | "SETTINGS"
  | "EMAIL_TEMPLATE"
  | "AUTH"
  | "ORGANIZATION_MEMBER"
  | "MEMBER_INVITE";

export type AuditAction =
  | "CREATE"
  | "UPDATE"
  | "DELETE"
  | "SEND"
  | "VOID"
  | "PAY"
  | "REFUND"
  | "SIGN"
  | "LOGIN"
  | "LOGOUT"
  | "RESET"
  | "TEST_SEND"
  | "STATUS_CHANGE"
  | "EXPORT";

interface LogAuditInput {
  organizationId: string;
  action: AuditAction;
  entityType: AuditEntity;
  entityId?: string | null;
  metadata?: Record<string, unknown> | null;
  userId?: string | null;
  actorEmail?: string | null;
  actorType?: string | null;
}

async function inferRequestContext(): Promise<{
  ipAddress?: string;
  userAgent?: string;
}> {
  try {
    const h = await headers();
    const ip =
      h.get("x-forwarded-for")?.split(",")[0]?.trim() ||
      h.get("x-real-ip") ||
      undefined;
    const ua = h.get("user-agent") || undefined;
    return { ipAddress: ip, userAgent: ua };
  } catch {
    return {};
  }
}

/** Keys that must never appear in audit metadata (AGENTS.md hard rule #10). */
const AUDIT_METADATA_REDACT_KEYS = new Set([
  "stripeSecretKey",
  "stripeWebhookSecret",
  "smtpPass",
  "twilioToken",
]);

function redactAuditMetadata(
  meta: Record<string, unknown> | null | undefined
): Record<string, unknown> | null {
  if (meta == null) return null;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(meta)) {
    if (AUDIT_METADATA_REDACT_KEYS.has(k)) {
      out[k] = "[REDACTED]";
      continue;
    }
    if (v !== null && typeof v === "object" && !Array.isArray(v)) {
      out[k] = redactAuditMetadata(v as Record<string, unknown>);
    } else if (Array.isArray(v)) {
      out[k] = v.map((item) =>
        item !== null && typeof item === "object" && !Array.isArray(item)
          ? redactAuditMetadata(item as Record<string, unknown>)
          : item
      );
    } else {
      out[k] = v;
    }
  }
  return out;
}

/**
 * Records an audit-log entry. Designed to never throw - auditing failures
 * must not block business operations.
 *
 * If `userId`/`actorEmail`/`actorType` are not supplied, they are inferred
 * from the current Auth.js session.
 */
export async function logAudit(input: LogAuditInput): Promise<void> {
  try {
    let { userId, actorEmail, actorType } = input;

    if (userId === undefined || actorEmail === undefined) {
      try {
        const session = await auth();
        if (session?.user) {
          if (userId === undefined) userId = session.user.id ?? null;
          if (actorEmail === undefined) actorEmail = session.user.email ?? null;
          if (actorType === undefined) {
            actorType = (session.user as { userType?: string }).userType ?? null;
          }
        }
      } catch {
        // best-effort; auth() may be unavailable in workers
      }
    }

    const ctx = await inferRequestContext();
    const metadata = redactAuditMetadata(input.metadata ?? undefined);

    await prisma.auditLog.create({
      data: {
        organizationId: input.organizationId,
        userId: userId ?? null,
        actorEmail: actorEmail ?? null,
        actorType: actorType ?? null,
        action: input.action,
        entityType: input.entityType,
        entityId: input.entityId ?? null,
        metadata: metadata as never,
        ipAddress: ctx.ipAddress ?? null,
        userAgent: ctx.userAgent ?? null,
      },
    });
  } catch (err) {
    console.error("[AuditLog] Failed to record audit entry:", err);
  }
}
