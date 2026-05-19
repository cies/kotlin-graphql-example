/**
 * Standalone worker process. Run alongside the Next.js server to execute
 * scheduled jobs (FX refresh, recurring invoices, auto-billing, reminders,
 * overdue invoices, status digests).
 *
 * Usage:
 *   npm run workers
 *
 * Environment:
 *   REDIS_URL          - Redis connection (defaults to redis://localhost:6381)
 *   DATABASE_URL       - Postgres connection
 *   EXCHANGERATE_API_KEY - optional, for daily FX rates
 *   RECURRING_INVOICE_CRON - optional cron pattern; if unset, non-production uses every 60s, production uses daily 07:00
 */
import "dotenv/config";
import { startAllWorkers, shutdownWorkers } from "@/lib/workers";

async function main() {
  console.log("[Workers] Booting standalone worker process...");
  await startAllWorkers();
  console.log("[Workers] Ready. Waiting for jobs (Ctrl+C to stop).");
}

main().catch((err) => {
  console.error("[Workers] Fatal startup error:", err);
  process.exit(1);
});

let shuttingDown = false;
async function handleSignal(signal: string) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`[Workers] Received ${signal}. Shutting down...`);
  try {
    await shutdownWorkers();
  } catch (err) {
    console.error("[Workers] Error during shutdown:", err);
  } finally {
    process.exit(0);
  }
}

process.on("SIGINT", () => void handleSignal("SIGINT"));
process.on("SIGTERM", () => void handleSignal("SIGTERM"));
