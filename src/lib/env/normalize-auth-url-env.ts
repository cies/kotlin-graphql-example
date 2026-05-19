import {
  ENV_APP_URL,
  ENV_AUTH_URL,
  ENV_NEXTAUTH_URL,
  ENV_NEXT_PUBLIC_APP_URL,
  ENV_PUBLIC_BASE_URL,
} from "@/lib/env/runtime-auth-env";

/** Keys must not be string literals `*_URL` or Next may inline build-time env. */
const URL_ENV_KEYS = [
  ENV_AUTH_URL,
  ENV_NEXTAUTH_URL,
  ENV_NEXT_PUBLIC_APP_URL,
  ENV_APP_URL,
  ENV_PUBLIC_BASE_URL,
] as const;

/**
 * PaaS / copy-paste mistakes often yield values like `https, https://host` or other
 * non-URL strings. Auth.js calls `new URL(AUTH_URL)` (no try/catch) in `reqWithEnvURL`
 * and `createActionURL`, which crashes the auth route.
 */
export function normalizeAuthUrlEnv(): void {
  for (const key of URL_ENV_KEYS) {
    const raw = process.env[key];
    if (typeof raw !== "string" || !raw.trim()) continue;
    const trimmed = raw.trim();
    let needsFix = trimmed.includes(",");
    if (!needsFix) {
      try {
        void new URL(trimmed);
        continue;
      } catch {
        needsFix = true;
      }
    }
    if (!needsFix) continue;

    const m = trimmed.match(/https?:\/\/[^\s,]+/i);
    if (!m) continue;
    const candidate = m[0].replace(/\/$/, "");
    try {
      void new URL(candidate);
      process.env[key] = candidate;
    } catch {
      /* keep original */
    }
  }
}
