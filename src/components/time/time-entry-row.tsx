import * as React from "react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils/cn";

function accentColorForKey(key: string): string {
  let h = 0;
  for (let i = 0; i < key.length; i++) {
    h = (h * 31 + key.charCodeAt(i)) | 0;
  }
  const hue = Math.abs(h) % 360;
  return `hsl(${hue} 62% 42%)`;
}

export interface TimeEntryRowProps {
  accentKey: string;
  primaryText: string;
  subtitle: string;
  description?: string | null;
  durationLabel: string;
  isRunning?: boolean;
  billed: boolean;
  actions?: React.ReactNode;
  className?: string;
}

export function TimeEntryRow({
  accentKey,
  primaryText,
  subtitle,
  description,
  durationLabel,
  isRunning = false,
  billed,
  actions,
  className,
}: TimeEntryRowProps) {
  return (
    <div
      className={cn(
        "flex items-start gap-3 py-3.5 first:pt-0 border-b border-[var(--border)] last:border-b-0",
        className
      )}
    >
      <span
        className="mt-1.5 h-2 w-2 shrink-0 rounded-full"
        style={{ backgroundColor: accentColorForKey(accentKey) }}
        aria-hidden
      />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold text-foreground leading-snug">{primaryText}</p>
        <p className="text-xs text-[var(--muted-foreground)] mt-0.5">{subtitle}</p>
        {description ? (
          <p className="text-xs text-[var(--muted-foreground)] mt-1 line-clamp-2">{description}</p>
        ) : null}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <div className="text-right">
          {isRunning ? (
            <span className="text-sm text-emerald-600 font-medium animate-pulse">Running…</span>
          ) : (
            <span className="text-sm font-medium text-[var(--muted-foreground)]">{durationLabel}</span>
          )}
        </div>
        {billed ? <Badge variant="success">Billed</Badge> : null}
        {actions}
      </div>
    </div>
  );
}
