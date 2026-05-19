/** Side-effect import: run before `handlers` so `AUTH_URL` is valid for next-auth `reqWithEnvURL`. */
import { normalizeAuthUrlEnv } from "./normalize-auth-url-env";

normalizeAuthUrlEnv();
