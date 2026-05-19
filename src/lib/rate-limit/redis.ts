import IORedis from "ioredis";

const globalForRl = globalThis as unknown as {
  rateLimitRedis?: IORedis;
};

/**
 * Fixed-window rate limiter backed by Redis (shared with BullMQ host).
 * If Redis is unavailable or misconfigured, requests are allowed (fail-open).
 */
function getClient(): IORedis | null {
  const url = process.env.REDIS_URL?.trim();
  if (!url) return null;
  if (!globalForRl.rateLimitRedis) {
    globalForRl.rateLimitRedis = new IORedis(url, {
      maxRetriesPerRequest: 2,
      enableReadyCheck: true,
      lazyConnect: true,
    });
  }
  return globalForRl.rateLimitRedis;
}

/**
 * @returns true if under limit, false if rate limited
 */
export async function assertRateLimit(
  bucket: string,
  max: number,
  windowSec: number
): Promise<boolean> {
  const r = getClient();
  if (!r) return true;
  const key = `rl:v1:${bucket}`;
  try {
    const n = await r.incr(key);
    if (n === 1) await r.expire(key, windowSec);
    return n <= max;
  } catch {
    return true;
  }
}
