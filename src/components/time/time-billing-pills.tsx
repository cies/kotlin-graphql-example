"use client";

import { useRouter, usePathname, useSearchParams } from "next/navigation";
import { cn } from "@/lib/utils/cn";

export function TimeBillingPills() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const status = searchParams.get("status") ?? "unbilled";

  const setStatus = (v: "unbilled" | "billed") => {
    const sp = new URLSearchParams(searchParams.toString());
    sp.set("status", v);
    sp.delete("page");
    router.replace(`${pathname}?${sp.toString()}`, { scroll: false });
  };

  return (
    <div className="flex rounded-lg border border-[var(--border)] p-0.5 bg-[var(--muted)]/40">
      <button
        type="button"
        onClick={() => setStatus("unbilled")}
        className={cn(
          "rounded-md px-3 py-1 text-xs font-medium transition-colors",
          status === "unbilled"
            ? "bg-[var(--primary)] text-white shadow-sm"
            : "text-[var(--muted-foreground)] hover:text-foreground"
        )}
      >
        Unbilled
      </button>
      <button
        type="button"
        onClick={() => setStatus("billed")}
        className={cn(
          "rounded-md px-3 py-1 text-xs font-medium transition-colors",
          status === "billed"
            ? "bg-[var(--primary)] text-white shadow-sm"
            : "text-[var(--muted-foreground)] hover:text-foreground"
        )}
      >
        Billed
      </button>
    </div>
  );
}
