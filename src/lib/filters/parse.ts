/**
 * Helpers for parsing URL searchParams into typed filter objects
 * shared across all list views.
 *
 * Pages always pass `searchParams` (a `Promise<{ [key: string]: string | string[] | undefined }>`)
 * directly into one of these parsers.
 */

export type RawSearchParams = Record<string, string | string[] | undefined>;

function getOne(sp: RawSearchParams, key: string): string | undefined {
  const v = sp[key];
  if (Array.isArray(v)) return v[0];
  return v;
}

function getMany(sp: RawSearchParams, key: string): string[] {
  const v = sp[key];
  if (Array.isArray(v)) return v;
  if (typeof v === "string" && v.length > 0) return v.split(",").filter(Boolean);
  return [];
}

export interface CommonFilters {
  q?: string;
  from?: Date;
  to?: Date;
  customerId?: string;
  currency?: string;
  status?: string[];
  page: number;
  perPage: number;
}

const DEFAULT_PER_PAGE = 50;
const MAX_PER_PAGE = 200;

function parseDate(value: string | undefined): Date | undefined {
  if (!value) return undefined;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? undefined : d;
}

/** Canonical billed / unbilled from `status` query param; invalid or absent → unbilled (for export and APIs). */
export function parseTimeEntryBillingFilter(sp: RawSearchParams): "billed" | "unbilled" {
  const v = getOne(sp, "status");
  if (v === "billed") return "billed";
  return "unbilled";
}

/** Returns null if `status` is missing or not billed/unbilled (time page should redirect to default). */
export function getTimeBillingStatusFromUrl(sp: RawSearchParams): "billed" | "unbilled" | null {
  const v = getOne(sp, "status");
  if (v === "billed" || v === "unbilled") return v;
  return null;
}

export function parseCommonFilters(sp: RawSearchParams): CommonFilters {
  const page = Math.max(1, parseInt(getOne(sp, "page") ?? "1", 10) || 1);
  const perPageRaw = parseInt(getOne(sp, "perPage") ?? `${DEFAULT_PER_PAGE}`, 10);
  const perPage = Math.min(
    MAX_PER_PAGE,
    Math.max(10, Number.isNaN(perPageRaw) ? DEFAULT_PER_PAGE : perPageRaw)
  );

  return {
    q: getOne(sp, "q")?.trim() || undefined,
    from: parseDate(getOne(sp, "from")),
    to: parseDate(getOne(sp, "to")),
    customerId: getOne(sp, "customerId") || undefined,
    currency: getOne(sp, "currency") || undefined,
    status: getMany(sp, "status"),
    page,
    perPage,
  };
}

/**
 * Builds a URLSearchParams from the active filters. Useful for CSV export
 * links that reuse the current view filters.
 */
export function filtersToSearchParams(filters: Partial<CommonFilters>): string {
  const sp = new URLSearchParams();
  if (filters.q) sp.set("q", filters.q);
  if (filters.from) sp.set("from", filters.from.toISOString().slice(0, 10));
  if (filters.to) sp.set("to", filters.to.toISOString().slice(0, 10));
  if (filters.customerId) sp.set("customerId", filters.customerId);
  if (filters.currency) sp.set("currency", filters.currency);
  if (filters.status && filters.status.length) {
    sp.set("status", filters.status.join(","));
  }
  return sp.toString();
}
