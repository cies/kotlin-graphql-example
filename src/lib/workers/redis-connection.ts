import IORedis from "ioredis";

/**
 * New BullMQ connections per Queue/Worker (do not share one IORedis between both).
 * Use full REDIS_URL so ports, DB index (/0), and passwords parse correctly.
 */
export function createBullmqConnection(): IORedis {
  const url = process.env.REDIS_URL?.trim() || "redis://127.0.0.1:6381";
  return new IORedis(url, {
    maxRetriesPerRequest: null,
    enableReadyCheck: false,
  });
}
