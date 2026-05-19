"use client";

import { useState, useTransition } from "react";
import { Switch } from "@/components/ui/switch";
import { setOrgPremiumComplimentary, type PlatformOrgRow } from "@/lib/actions/platform-admin";

export function ComplimentaryPremiumTable({ initialRows }: { initialRows: PlatformOrgRow[] }) {
  const [rows, setRows] = useState(initialRows);
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  function toggle(row: PlatformOrgRow, enabled: boolean) {
    setError(null);
    setPendingId(row.id);
    startTransition(async () => {
      const res = await setOrgPremiumComplimentary(row.id, enabled);
      setPendingId(null);
      if ("error" in res) {
        setError(res.error);
        return;
      }
      setRows((prev) =>
        prev.map((r) => (r.id === row.id ? { ...r, premiumComplimentary: enabled } : r))
      );
    });
  }

  return (
    <div className="space-y-3">
      {error && (
        <p className="text-sm text-[var(--destructive)]" role="alert">
          {error}
        </p>
      )}
      <div className="rounded-lg border border-[var(--border)] overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-[var(--border)] bg-[var(--muted)]">
              <th className="text-left p-3 font-medium">Organization</th>
              <th className="text-left p-3 font-medium">Slug</th>
              <th className="text-center p-3 font-medium">Stripe Premium</th>
              <th className="text-center p-3 font-medium">Complimentary</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} className="border-b border-[var(--border)] last:border-0">
                <td className="p-3">{row.name}</td>
                <td className="p-3 font-mono text-xs text-[var(--muted-foreground)]">{row.slug}</td>
                <td className="p-3 text-center">{row.subscriptionPremium ? "Yes" : "-"}</td>
                <td className="p-3">
                  <div className="flex justify-center">
                    <Switch
                      checked={row.premiumComplimentary}
                      disabled={isPending && pendingId === row.id}
                      onCheckedChange={(v) => toggle(row, v)}
                      aria-label={`Complimentary premium for ${row.name}`}
                    />
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-[var(--muted-foreground)]">
        Complimentary Premium unlocks the same features as a paid subscription without Stripe. Use env{" "}
        <code className="font-mono">PLATFORM_ADMIN_EMAILS</code> to control who sees this page.
      </p>
    </div>
  );
}
