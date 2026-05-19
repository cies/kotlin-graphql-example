import "server-only";

import { headers } from "next/headers";
import { getPublicAppOriginFromEnv } from "@/lib/env/public-app-url";

function nonEmpty(v: string | undefined | null): string | undefined {
  if (v === undefined || v === null || v === "") return undefined;
  const t = v.trim();
  return t || undefined;
}

/**
 * Server Actions / Route Handlers: derives `https://host` from `Host` /
 * `X-Forwarded-Host` when env is unset (Docker behind a reverse proxy).
 */
export async function resolvePublicAppOrigin(): Promise<string> {
  const fromEnv = getPublicAppOriginFromEnv();
  if (fromEnv) return fromEnv;

  try {
    const h = await headers();
    const host =
      nonEmpty(h.get("x-forwarded-host")?.split(",")[0]) ?? nonEmpty(h.get("host"));
    if (host) {
      const protoHead = h.get("x-forwarded-proto")?.split(",")[0]?.trim();
      const local =
        host.startsWith("localhost") ||
        host.startsWith("127.") ||
        host.includes(".local");
      const proto =
        protoHead === "http" || protoHead === "https"
          ? protoHead
          : local
            ? "http"
            : "https";
      return `${proto}://${host}`;
    }
  } catch {
    /* headers() unavailable outside a request */
  }

  if (process.env.NODE_ENV !== "production") {
    return "http://localhost:3000";
  }
  console.error(
    "[crm] Cannot resolve public app origin (no env URL and no Host header). Set NEXT_PUBLIC_APP_URL or APP_URL."
  );
  return "";
}

export async function resolveAbsoluteAppUrl(pathnameAndQuery: string): Promise<string> {
  const origin = await resolvePublicAppOrigin();
  const path = pathnameAndQuery.startsWith("/")
    ? pathnameAndQuery
    : `/${pathnameAndQuery}`;
  return `${origin}${path}`;
}
