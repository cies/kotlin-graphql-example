import { normalizeAuthUrlEnv } from "@/lib/env/normalize-auth-url-env";

export async function register() {
  if (process.env.NEXT_RUNTIME === "edge") return;
  normalizeAuthUrlEnv();
}
