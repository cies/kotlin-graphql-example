/**
 * Next.js may inline `process.env.FOO` at build time. Docker/PaaS often inject
 * secrets only at container runtime, so those become `undefined` in the bundle.
 * Obfuscated keys force real `process.env` lookups when the module runs.
 */
const k = (parts: string[]) => parts.join("_");

export const ENV_AUTH_SECRET = k(["AUTH", "SECRET"]);
export const ENV_NEXTAUTH_SECRET = k(["NEXTAUTH", "SECRET"]);
export const ENV_AUTH_URL = k(["AUTH", "URL"]);
export const ENV_NEXTAUTH_URL = k(["NEXTAUTH", "URL"]);
export const ENV_NEXT_PUBLIC_APP_URL = k(["NEXT", "PUBLIC", "APP", "URL"]);
/** Optional site root (runtime); use when NEXT_PUBLIC_APP_URL is unset at build time. */
export const ENV_APP_URL = k(["APP", "URL"]);
export const ENV_PUBLIC_BASE_URL = k(["PUBLIC", "BASE", "URL"]);
export const ENV_VERCEL_URL = k(["VERCEL", "URL"]);

function nonEmpty(v: string | undefined): string | undefined {
  if (v === undefined || v === "") return undefined;
  return v;
}

/** Auth signing secret - must resolve at container runtime, not Docker build time. */
export function getAuthSecret(): string | undefined {
  return nonEmpty(process.env[ENV_AUTH_SECRET]) ?? nonEmpty(process.env[ENV_NEXTAUTH_SECRET]);
}
