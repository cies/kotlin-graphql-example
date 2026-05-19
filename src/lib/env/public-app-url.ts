import {
  ENV_APP_URL,
  ENV_AUTH_URL,
  ENV_NEXTAUTH_URL,
  ENV_NEXT_PUBLIC_APP_URL,
  ENV_PUBLIC_BASE_URL,
  ENV_VERCEL_URL,
} from "@/lib/env/runtime-auth-env";

function nonEmpty(v: string | undefined | null): string | undefined {
  if (v === undefined || v === null || v === "") return undefined;
  const t = v.trim();
  return t || undefined;
}

function vercelHost(): string | undefined {
  const v = nonEmpty(process.env[ENV_VERCEL_URL]);
  if (!v) return undefined;
  const host = v.replace(/^https?:\/\//i, "").replace(/\/.*$/, "");
  return host || undefined;
}

/** First non-empty URL-ish env (runtime bracket reads). */
function pickRawPublicBase(): string | undefined {
  const vercel = vercelHost();
  return (
    nonEmpty(process.env[ENV_NEXT_PUBLIC_APP_URL]) ??
    nonEmpty(process.env[ENV_APP_URL]) ??
    nonEmpty(process.env[ENV_PUBLIC_BASE_URL]) ??
    nonEmpty(process.env[ENV_AUTH_URL]) ??
    nonEmpty(process.env[ENV_NEXTAUTH_URL]) ??
    (vercel ? `https://${vercel}` : undefined)
  );
}

function rawToOrigin(raw: string): string {
  try {
    const withScheme = /^https?:\/\//i.test(raw) ? raw : `https://${raw}`;
    const u = new URL(withScheme);
    if (u.protocol !== "http:" && u.protocol !== "https:") return "";
    return u.origin;
  } catch {
    return "";
  }
}

/** Origin from env / deployment only (no request, no window). */
export function getPublicAppOriginFromEnv(): string {
  const raw = pickRawPublicBase();
  if (!raw) return "";
  const origin = rawToOrigin(raw);
  if (!origin) {
    console.error("[crm] Invalid public URL in APP_URL / AUTH_URL / NEXT_PUBLIC_APP_URL");
    return "";
  }
  return origin;
}

/**
 * Public origin for links and Stripe redirects.
 * - Env / VERCEL_URL first
 * - **Browser**: falls back to `window.location.origin` so client bundles work without inlined env
 * - **Server** without env: returns `""` (use `resolvePublicAppOrigin` from `@/lib/env/resolve-public-app-url.server` in Server Actions / Routes)
 */
export function getPublicAppOrigin(): string {
  const fromEnv = getPublicAppOriginFromEnv();
  if (fromEnv) return fromEnv;
  if (typeof window !== "undefined") {
    return window.location.origin;
  }
  if (process.env.NODE_ENV !== "production") {
    return "http://localhost:3000";
  }
  console.error(
    "[crm] Set NEXT_PUBLIC_APP_URL, APP_URL, or AUTH_URL to your public site root (e.g. https://platform.example.com)."
  );
  return "";
}

/** Absolute URL for a path starting with `/` (sync; on server prefer `resolveAbsoluteAppUrl` from `@/lib/env/resolve-public-app-url.server`). */
export function absoluteAppUrl(pathnameAndQuery: string): string {
  const origin = getPublicAppOrigin();
  const path = pathnameAndQuery.startsWith("/")
    ? pathnameAndQuery
    : `/${pathnameAndQuery}`;
  return `${origin}${path}`;
}

export function isAbsoluteHttpUrl(url: string): boolean {
  return /^https:\/\//i.test(url) || /^http:\/\//i.test(url);
}
