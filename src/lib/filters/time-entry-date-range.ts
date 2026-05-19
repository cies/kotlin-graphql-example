import type { Prisma } from "@prisma/client";

function utcDayStart(d: Date): Date {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
}

function utcDayEnd(d: Date): Date {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), 23, 59, 59, 999));
}

/**
 * UTC calendar YYYY-MM-DD for invoices / emails (aligned with {@link timeEntryWhereForDateRange}).
 */
export function invoiceTimeEntryCalendarIsoDate(entry: {
  loggedDate?: Date | null;
  startedAt?: Date | null;
  endedAt?: Date | null;
  createdAt?: Date | null;
}): string {
  const ref =
    entry.loggedDate ?? entry.startedAt ?? entry.endedAt ?? entry.createdAt ?? new Date();
  return new Date(ref).toISOString().slice(0, 10)!;
}

/**
 * Stable ascending order by UTC calendar ISO date ({@link invoiceTimeEntryCalendarIsoDate}),
 * then user name, then description.
 */
export function sortInvoiceEntriesByCalendarAsc<
  T extends { date: string; userName: string; description: string | null },
>(entries: T[]): T[] {
  return [...entries].sort((a, b) => {
    const d = a.date.localeCompare(b.date);
    if (d !== 0) return d;
    const u = a.userName.localeCompare(b.userName);
    if (u !== 0) return u;
    return (a.description ?? "").localeCompare(b.description ?? "");
  });
}

/**
 * Combines breakdown rows that share the same UTC calendar date and staff name.
 * Minute totals stay the same; distinct descriptions are joined with "; ".
 * Returned rows are sorted like {@link sortInvoiceEntriesByCalendarAsc}.
 */
export function mergeInvoiceEntriesSameCalendarDayAndUser<
  T extends { date: string; minutes: number; userName: string; description: string | null },
>(entries: T[]): T[] {
  const groups = new Map<
    string,
    { date: string; userName: string; minutes: number; descriptions: Set<string> }
  >();
  for (const e of entries) {
    const key = `${e.date}\0${e.userName}`;
    let g = groups.get(key);
    if (!g) {
      g = { date: e.date, userName: e.userName, minutes: 0, descriptions: new Set<string>() };
      groups.set(key, g);
    }
    g.minutes += e.minutes;
    const d = e.description?.trim();
    if (d) g.descriptions.add(d);
  }
  const merged: T[] = [...groups.values()].map((row) => {
    let description: string | null = null;
    if (row.descriptions.size > 0) {
      description = [...row.descriptions].sort((a, b) => a.localeCompare(b)).join("; ");
    }
    return {
      date: row.date,
      minutes: row.minutes,
      userName: row.userName,
      description,
    } as T;
  });
  return sortInvoiceEntriesByCalendarAsc(merged);
}

/**
 * Matches entries whose calendar work date falls in [from, to] (inclusive, UTC days).
 * Manual entries use `loggedDate` when set; legacy manual rows use `createdAt`; timers use `startedAt`.
 */
export function timeEntryWhereForDateRange(
  from?: Date,
  to?: Date
): Prisma.TimeEntryWhereInput | undefined {
  if (!from && !to) return undefined;

  const branches: Prisma.TimeEntryWhereInput[] = [];

  const loggedDateFilter: Prisma.DateTimeNullableFilter = {};
  if (from) loggedDateFilter.gte = utcDayStart(from);
  if (to) loggedDateFilter.lte = utcDayStart(to);

  branches.push({ loggedDate: loggedDateFilter });

  branches.push({
    AND: [
      { manualMinutes: { not: null } },
      { loggedDate: null },
      {
        createdAt: {
          ...(from ? { gte: utcDayStart(from) } : {}),
          ...(to ? { lte: utcDayEnd(to) } : {}),
        },
      },
    ],
  });

  branches.push({
    AND: [
      { manualMinutes: null },
      { startedAt: { not: null } },
      {
        startedAt: {
          ...(from ? { gte: utcDayStart(from) } : {}),
          ...(to ? { lte: utcDayEnd(to) } : {}),
        },
      },
    ],
  });

  return { OR: branches };
}
