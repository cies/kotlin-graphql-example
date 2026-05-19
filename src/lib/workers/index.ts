import type { Worker } from "bullmq";
import { createBullmqConnection } from "./redis-connection";
import { scheduleDailyFxRefresh, startFxWorker, fxQueue } from "./fx-worker";
import {
  scheduleRecurringInvoiceWorker,
  startRecurringInvoiceWorker,
  recurringInvoiceQueue,
} from "./recurring-invoice-worker";
import { scheduleAutoBillingWorker, startAutoBillingWorker, autoBillingQueue } from "./auto-billing-worker";
import {
  scheduleReminderWorkers,
  startReminderWorker,
  startOverdueInvoiceWorker,
  reminderQueue,
  overdueInvoiceQueue,
} from "./reminder-worker";
import { scheduleStatusEmailWorker, startStatusEmailWorker, statusEmailQueue } from "./status-email-worker";
import { redactConnectionUrlForLog } from "@/lib/utils/redact-connection-url";

let startedWorkers: Worker[] = [];

export async function startAllWorkers() {
  console.log("[Workers] Starting all workers...");

  const probe = createBullmqConnection();
  try {
    const pong = await probe.ping();
    if (pong !== "PONG") console.warn("[Workers] Redis ping unexpected:", pong);
    else {
      const raw = process.env.REDIS_URL?.trim() || "redis://127.0.0.1:6381";
      console.log("[Workers] Redis connected:", redactConnectionUrlForLog(raw));
    }
  } catch (err) {
    console.error(
      "[Workers] Redis connection failed. Start Redis (e.g. `docker compose up -d`) and set REDIS_URL if needed.",
      err
    );
    throw err;
  } finally {
    await probe.quit();
  }

  // Schedule repeating jobs
  await scheduleDailyFxRefresh();
  await scheduleRecurringInvoiceWorker();
  await scheduleAutoBillingWorker();
  await scheduleReminderWorkers();
  await scheduleStatusEmailWorker();

  // Start workers
  startedWorkers = [
    startFxWorker(),
    startRecurringInvoiceWorker(),
    startAutoBillingWorker(),
    startReminderWorker(),
    startOverdueInvoiceWorker(),
    startStatusEmailWorker(),
  ];

  console.log("[Workers] All workers started.");
}

/** Close BullMQ workers and queues (SIGINT/SIGTERM). */
export async function shutdownWorkers(): Promise<void> {
  await Promise.all(startedWorkers.map((w) => w.close()));
  startedWorkers = [];
  await Promise.all([
    fxQueue.close(),
    recurringInvoiceQueue.close(),
    autoBillingQueue.close(),
    reminderQueue.close(),
    overdueInvoiceQueue.close(),
    statusEmailQueue.close(),
  ]);
}
