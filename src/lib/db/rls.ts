import { prisma } from "./prisma";
import type { Prisma } from "@prisma/client";

/**
 * Runs `fn` inside a Postgres transaction with `SET LOCAL app.org_id = '<id>'`,
 * activating the optional Row-Level Security policies defined in
 * `prisma/sql/rls-policies.sql`.
 *
 * The variable scope is per-transaction, so it resets automatically and is
 * safe to use under concurrent connections.
 *
 * Use this helper when you want strict tenant isolation enforced at the
 * database level (e.g. inside a non-superuser app role). The application
 * also enforces tenancy at the Server-Action layer; this is defence-in-depth.
 */
export async function withRls<T>(
  organizationId: string,
  fn: (tx: Prisma.TransactionClient) => Promise<T>
): Promise<T> {
  return prisma.$transaction(async (tx) => {
    // SET LOCAL - scoped to this transaction only.
    // The org id is a CUID so quoting is safe, but we still escape single quotes
    // defensively in case a different ID format is used in future.
    const safeId = organizationId.replace(/'/g, "''");
    await tx.$executeRawUnsafe(`SET LOCAL app.org_id = '${safeId}'`);
    return fn(tx);
  });
}
