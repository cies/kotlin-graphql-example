import { Queue, Worker } from "bullmq";
import { prisma } from "@/lib/db/prisma";
import { createBullmqConnection } from "./redis-connection";
import { fetchEurLiveQuotes } from "@/lib/fx/fetch-eur-live-quotes";

const CURRENCIES = ["USD", "GBP", "CHF", "CAD", "AUD"] as const;

export const fxQueue = new Queue("fx-rates", { connection: createBullmqConnection() });

export async function scheduleDailyFxRefresh() {
  await fxQueue.add(
    "refresh",
    {},
    {
      repeat: {
        pattern: "0 6 * * *",
      },
      jobId: "daily-fx-refresh",
    }
  );
}

export function startFxWorker() {
  const worker = new Worker(
    "fx-rates",
    async () => {
      const orgs = await prisma.organization.findMany({
        select: { id: true },
      });

      if (orgs.length === 0) return;

      const resolved = await fetchEurLiveQuotes();
      if (!resolved || Object.keys(resolved).length === 0) {
        console.warn("[FX Worker] FX fetch returned no quotes");
        return;
      }

      for (const org of orgs) {
        try {
          await Promise.all(
            CURRENCIES.map((currency) => {
              const rate = resolved[`EUR${currency}`];
              if (!rate) return Promise.resolve();
              return prisma.fxRate.upsert({
                where: {
                  organizationId_baseCurrency_quoteCurrency: {
                    organizationId: org.id,
                    baseCurrency: "EUR",
                    quoteCurrency: currency,
                  },
                },
                update: { rate, fetchedAt: new Date() },
                create: {
                  organizationId: org.id,
                  baseCurrency: "EUR",
                  quoteCurrency: currency,
                  rate,
                },
              });
            })
          );
        } catch (err) {
          console.error(`FX refresh failed for org ${org.id}:`, err);
        }
      }
    },
    { connection: createBullmqConnection() }
  );

  worker.on("completed", () => {
    console.log("[FX Worker] FX rates refreshed for all orgs");
  });

  worker.on("failed", (job, err) => {
    console.error("[FX Worker] Job failed:", err);
  });

  return worker;
}
