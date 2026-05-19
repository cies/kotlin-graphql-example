import type { ReactNode } from "react";

/**
 * Standalone shell for /admin - no org-scoped sidebar (staff layout is under /[orgSlug]).
 */
export default function AdminLayout({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-[var(--background)] text-[var(--foreground)]">
      {children}
    </div>
  );
}
