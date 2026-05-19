/**
 * Comma-separated list in PLATFORM_ADMIN_EMAILS - accounts that may use /admin
 * to grant complimentary Premium to any organization.
 */
export function getPlatformAdminEmailSet(): Set<string> {
  const raw = process.env.PLATFORM_ADMIN_EMAILS ?? "";
  return new Set(
    raw
      .split(",")
      .map((s) => s.trim().toLowerCase())
      .filter(Boolean)
  );
}

export function isPlatformAdminEmail(email: string | null | undefined): boolean {
  if (!email) return false;
  const set = getPlatformAdminEmailSet();
  if (set.size === 0) return false;
  return set.has(email.trim().toLowerCase());
}
