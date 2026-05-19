/**
 * When unset or not exactly `"true"`, public self-service signup is disabled.
 */
export function isPublicRegistrationEnabled(): boolean {
  return process.env.ENABLE_PUBLIC_REGISTRATION === "true";
}
