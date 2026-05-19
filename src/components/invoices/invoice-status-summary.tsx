import Link from "next/link";
import type { InvoiceStatus } from "@prisma/client";
import { cn } from "@/lib/utils/cn";

const BUCKETS: {
  label: string;
  status: InvoiceStatus;
  labelClass: string;
}[] = [
  { label: "Unpaid", status: "SENT", labelClass: "text-red-600 dark:text-red-400" },
  { label: "Paid", status: "PAID", labelClass: "text-green-600 dark:text-green-400" },
  {
    label: "Partially Paid",
    status: "PARTIAL",
    labelClass: "text-orange-600 dark:text-orange-400",
  },
  {
    label: "Overdue",
    status: "OVERDUE",
    labelClass: "text-amber-800 dark:text-amber-500",
  },
  { label: "Draft", status: "DRAFT", labelClass: "text-[var(--foreground)]/70" },
  {
    label: "Refunded",
    status: "REFUNDED",
    labelClass: "text-orange-700 dark:text-orange-400",
  },
  {
    label: "Chargeback",
    status: "CHARGEBACK",
    labelClass: "text-red-700 dark:text-red-400",
  },
];

export interface InvoiceStatusSummaryProps {
  orgSlug: string;
  counts: Record<InvoiceStatus, number>;
  /** URL `status` filter values - when exactly one matches a bucket, that card is highlighted. */
  activeStatusFilter: string[];
}

export function InvoiceStatusSummary({
  orgSlug,
  counts,
  activeStatusFilter,
}: InvoiceStatusSummaryProps) {
  const total = (Object.keys(counts) as InvoiceStatus[]).reduce((s, k) => s + counts[k], 0);

  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-4 xl:grid-cols-7 mb-6">
      {BUCKETS.map(({ label, status, labelClass }) => {
        const n = counts[status] ?? 0;
        const pct = total > 0 ? (n / total) * 100 : 0;
        const pctText = `${pct.toFixed(2)}%`;
        const isActive =
          activeStatusFilter.length === 1 && activeStatusFilter[0] === status;

        return (
          <Link
            key={status}
            href={`/${orgSlug}/invoices?status=${status}`}
            scroll={false}
            className={cn(
              "rounded-lg border border-[var(--border)] bg-[var(--card)] px-4 py-3 text-left transition-colors",
              "hover:bg-[var(--accent)]/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]",
              isActive && "ring-2 ring-[var(--primary)] border-[var(--primary)]"
            )}
          >
            <div className="flex flex-wrap items-baseline gap-x-1.5 gap-y-0">
              <span className={cn("text-sm font-semibold", labelClass)}>{label}</span>
              <span className="text-xs text-[var(--muted-foreground)]">({pctText})</span>
            </div>
            <p className="mt-1 text-sm font-medium tabular-nums text-[var(--foreground)]">
              {n} / {total}
            </p>
          </Link>
        );
      })}
    </div>
  );
}
