/**
 * Shared logo conversion utility.
 * Accepts a URL (absolute https:// or relative /public path) pointing to
 * a PNG, JPEG, WebP, or SVG logo. Always returns a PNG Buffer at 320px wide.
 * Results are LRU-cached in memory (max 50 entries) to avoid repeated sharp/fetch
 * calls during a single deployment lifecycle. Entries expire after TTL on read.
 */
import sharp from "sharp";
import path from "path";
import fs from "fs/promises";

const CACHE_MAX = 50;
/** Max age for a cached PNG before re-fetching / re-rasterising. */
const LOGO_CACHE_TTL_MS = 24 * 60 * 60 * 1000;
const cache = new Map<string, { buf: Buffer; ts: number }>();

function evictIfNeeded() {
  if (cache.size >= CACHE_MAX) {
    const oldest = [...cache.entries()].sort((a, b) => a[1].ts - b[1].ts)[0];
    if (oldest) cache.delete(oldest[0]);
  }
}

async function fetchLogoBuffer(url: string): Promise<{ buf: Buffer; isSvg: boolean }> {
  // Handle relative paths (public folder)
  if (url.startsWith("/")) {
    const filePath = path.join(process.cwd(), "public", url);
    const buf = await fs.readFile(filePath);
    return { buf, isSvg: url.toLowerCase().endsWith(".svg") };
  }

  const res = await fetch(url, { next: { revalidate: 3600 } });
  if (!res.ok) throw new Error(`Failed to fetch logo: ${res.status} ${url}`);
  const arrayBuf = await res.arrayBuffer();
  const buf = Buffer.from(arrayBuf);
  const contentType = res.headers.get("content-type") ?? "";
  const isSvg =
    contentType.includes("svg") ||
    url.toLowerCase().endsWith(".svg");
  return { buf, isSvg };
}

/**
 * Returns a PNG Buffer for the given logo URL.
 * SVG inputs are rasterised via sharp (librsvg).
 * Raster inputs are resized to max-width 320px and converted to PNG.
 * Pass `cacheKey` to differentiate logos without changing URL (e.g., org ID).
 */
export async function fetchAndConvertLogo(
  url: string,
  cacheKey?: string
): Promise<Buffer> {
  const key = cacheKey ? `${cacheKey}::${url}` : url;

  const cached = cache.get(key);
  if (cached) {
    if (Date.now() - cached.ts <= LOGO_CACHE_TTL_MS) return cached.buf;
    cache.delete(key);
  }

  const { buf, isSvg } = await fetchLogoBuffer(url);

  let pipeline = sharp(buf, { density: isSvg ? 150 : undefined });
  pipeline = pipeline.resize({ width: 320, withoutEnlargement: !isSvg });
  const pngBuf = await pipeline.png().toBuffer();

  evictIfNeeded();
  cache.set(key, { buf: pngBuf, ts: Date.now() });
  return pngBuf;
}

/** Clears the in-memory logo cache (useful after logo URL changes). */
export function clearLogoCache(cacheKey?: string, url?: string) {
  if (cacheKey && url) {
    cache.delete(`${cacheKey}::${url}`);
  } else {
    cache.clear();
  }
}

/** Drop all cached logos for an org (used when `companyLogoUrl` may have changed). */
export function clearLogoCacheForOrg(organizationId: string) {
  const prefix = `${organizationId}::`;
  for (const k of cache.keys()) {
    if (k.startsWith(prefix)) cache.delete(k);
  }
}
