"use client";

import { useEffect, useState } from "react";
import { formatMinutes } from "@/lib/utils/format";

interface HourSummary {
  loggedMinutes: number;
  billedMinutes: number;
  paidMinutes: number;
  unbilledMinutes: number;
  unpaidBilledMinutes: number;
}

interface Props {
  orgSlug: string;
  projectId: string;
  taskId: string;
}

export function TaskHourSummary({ orgSlug, taskId }: Props) {
  const [summary, setSummary] = useState<HourSummary | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch(`/api/tasks/${taskId}/hours`)
      .then((r) => r.json())
      .then((data) => {
        setSummary(data);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, [taskId]);

  if (loading) return <p className="text-sm text-[var(--muted-foreground)]">Loading…</p>;
  if (!summary) return <p className="text-sm text-[var(--destructive)]">Failed to load.</p>;

  const rows = [
    { label: "Total logged", value: summary.loggedMinutes, color: "text-[var(--foreground)]" },
    { label: "Billed", value: summary.billedMinutes, color: "text-blue-600" },
    { label: "Paid", value: summary.paidMinutes, color: "text-emerald-600" },
    { label: "Unbilled", value: summary.unbilledMinutes, color: "text-amber-600" },
    { label: "Billed but unpaid", value: summary.unpaidBilledMinutes, color: "text-orange-600" },
  ];

  return (
    <div className="space-y-3">
      {rows.map((r) => (
        <div key={r.label} className="flex justify-between items-center">
          <span className="text-sm text-[var(--muted-foreground)]">{r.label}</span>
          <span className={`text-sm font-medium ${r.color}`}>
            {formatMinutes(r.value)}
          </span>
        </div>
      ))}
    </div>
  );
}
