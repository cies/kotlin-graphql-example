"use client";

import * as React from "react";
import { useRouter, usePathname, useSearchParams } from "next/navigation";
import { Search, X, Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

export interface FilterOption {
  value: string;
  label: string;
}

export interface FilterBarProps {
  searchPlaceholder?: string;
  /** When provided, the status select is rendered. */
  statuses?: FilterOption[];
  /** When provided, the customer select is rendered. */
  customers?: FilterOption[];
  /** When provided, the currency select is rendered. */
  currencies?: FilterOption[];
  /** When true, renders date-range inputs (from / to). */
  showDateRange?: boolean;
  /** Optional CSV-export URL relative to the app. The current querystring is appended automatically. */
  exportHref?: string;
  /** Render a button that resets the filters. */
  showReset?: boolean;
  /**
   * When set, `status` equal to this value does not count as an "active" filter
   * (so Reset stays hidden on the default billing view).
   */
  statusDefaultIgnored?: string;
  /**
   * When set, Reset navigates to `pathname?{this}` instead of stripping all params
   * (e.g. `status=unbilled` after clearing search / dates).
   */
  resetSearchDefaults?: string;
}

const DEFAULT_DEBOUNCE_MS = 300;

export function FilterBar({
  searchPlaceholder = "Search...",
  statuses,
  customers,
  currencies,
  showDateRange = false,
  exportHref,
  showReset = true,
  statusDefaultIgnored,
  resetSearchDefaults,
}: FilterBarProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const [searchValue, setSearchValue] = React.useState(searchParams.get("q") ?? "");

  React.useEffect(() => {
    setSearchValue(searchParams.get("q") ?? "");
  }, [searchParams]);

  const updateParam = React.useCallback(
    (key: string, value: string | null) => {
      const sp = new URLSearchParams(searchParams.toString());
      if (value && value.length > 0) {
        sp.set(key, value);
      } else {
        sp.delete(key);
      }
      sp.delete("page");
      const qs = sp.toString();
      router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
    },
    [router, pathname, searchParams]
  );

  React.useEffect(() => {
    const handle = setTimeout(() => {
      const current = searchParams.get("q") ?? "";
      if (searchValue === current) return;
      updateParam("q", searchValue || null);
    }, DEFAULT_DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [searchValue, searchParams, updateParam]);

  const hasActiveFilters = React.useMemo(() => {
    for (const key of ["q", "status", "customerId", "currency", "from", "to"]) {
      const val = searchParams.get(key);
      if (!val) continue;
      if (
        key === "status" &&
        statusDefaultIgnored &&
        val === statusDefaultIgnored
      ) {
        continue;
      }
      return true;
    }
    return false;
  }, [searchParams, statusDefaultIgnored]);

  const reset = () => {
    if (resetSearchDefaults) {
      router.replace(`${pathname}?${resetSearchDefaults}`, { scroll: false });
    } else {
      router.replace(pathname, { scroll: false });
    }
  };

  const exportUrl = React.useMemo(() => {
    if (!exportHref) return null;
    const qs = searchParams.toString();
    return qs ? `${exportHref}?${qs}` : exportHref;
  }, [exportHref, searchParams]);

  return (
    <div className="rounded-lg border border-[var(--border)] bg-[var(--card)] p-3 mb-4">
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex-1 min-w-[200px]">
          <label className="text-xs text-[var(--muted-foreground)] mb-1 block">
            Search
          </label>
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--muted-foreground)]" />
            <Input
              value={searchValue}
              onChange={(e) => setSearchValue(e.target.value)}
              placeholder={searchPlaceholder}
              className="pl-8"
            />
          </div>
        </div>

        {statuses && statuses.length > 0 && (
          <div className="min-w-[150px]">
            <label className="text-xs text-[var(--muted-foreground)] mb-1 block">
              Status
            </label>
            <Select
              value={searchParams.get("status") ?? "__all__"}
              onValueChange={(v) => updateParam("status", v === "__all__" ? null : v)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__all__">All statuses</SelectItem>
                {statuses.map((s) => (
                  <SelectItem key={s.value} value={s.value}>
                    {s.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}

        {customers && customers.length > 0 && (
          <div className="min-w-[200px]">
            <label className="text-xs text-[var(--muted-foreground)] mb-1 block">
              Customer
            </label>
            <Select
              value={searchParams.get("customerId") ?? "__all__"}
              onValueChange={(v) =>
                updateParam("customerId", v === "__all__" ? null : v)
              }
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__all__">All customers</SelectItem>
                {customers.map((c) => (
                  <SelectItem key={c.value} value={c.value}>
                    {c.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}

        {currencies && currencies.length > 0 && (
          <div className="min-w-[120px]">
            <label className="text-xs text-[var(--muted-foreground)] mb-1 block">
              Currency
            </label>
            <Select
              value={searchParams.get("currency") ?? "__all__"}
              onValueChange={(v) =>
                updateParam("currency", v === "__all__" ? null : v)
              }
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__all__">Any</SelectItem>
                {currencies.map((c) => (
                  <SelectItem key={c.value} value={c.value}>
                    {c.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}

        {showDateRange && (
          <>
            <div>
              <label className="text-xs text-[var(--muted-foreground)] mb-1 block">
                From
              </label>
              <Input
                type="date"
                value={searchParams.get("from") ?? ""}
                onChange={(e) => updateParam("from", e.target.value || null)}
                className="w-[150px]"
              />
            </div>
            <div>
              <label className="text-xs text-[var(--muted-foreground)] mb-1 block">
                To
              </label>
              <Input
                type="date"
                value={searchParams.get("to") ?? ""}
                onChange={(e) => updateParam("to", e.target.value || null)}
                className="w-[150px]"
              />
            </div>
          </>
        )}

        <div className="flex items-end gap-2 ml-auto">
          {showReset && hasActiveFilters && (
            <Button variant="outline" size="sm" onClick={reset}>
              <X className="h-4 w-4" />
              Reset
            </Button>
          )}
          {exportUrl && (
            <Button variant="outline" size="sm" asChild>
              <a href={exportUrl} download>
                <Download className="h-4 w-4" />
                Export CSV
              </a>
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
