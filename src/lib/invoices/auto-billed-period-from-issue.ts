/**
 * Billed period derived from the invoice issue date (UTC calendar day).
 * Org-level timezones are not modeled; matches HTML date inputs and worker `issuedAt`.
 *
 * - Issue on day 1: full previous calendar month (first–last day).
 * - Otherwise: period ends the day before issue; period starts the same calendar
 *   day one month earlier, with end-of-month clamping.
 */

function daysInMonthUTC(year: number, monthIndex0: number): number {
  return new Date(Date.UTC(year, monthIndex0 + 1, 0)).getUTCDate();
}

/** Add `delta` calendar months to a UTC calendar date (year, month0, day), clamping the day. */
export function addCalendarMonthsUTC(
  year: number,
  monthIndex0: number,
  day: number,
  delta: number
): { y: number; m: number; d: number } {
  const m = monthIndex0 + delta;
  const y = year + Math.floor(m / 12);
  const mn = ((m % 12) + 12) % 12;
  const dim = daysInMonthUTC(y, mn);
  const d = Math.min(day, dim);
  return { y, m: mn, d };
}

export function computeAutoBilledPeriodFromIssueDate(issuedAt: Date): {
  periodFrom: Date;
  periodTo: Date;
} {
  const y = issuedAt.getUTCFullYear();
  const m0 = issuedAt.getUTCMonth();
  const day = issuedAt.getUTCDate();

  if (day === 1) {
    const prevLast = new Date(Date.UTC(y, m0, 0));
    const prevFirst = new Date(Date.UTC(y, m0 - 1, 1));
    return { periodFrom: prevFirst, periodTo: prevLast };
  }

  const periodTo = new Date(Date.UTC(y, m0, day - 1));
  const ty = periodTo.getUTCFullYear();
  const tm = periodTo.getUTCMonth();
  const td = periodTo.getUTCDate();
  const { y: fy, m: fm, d: fd } = addCalendarMonthsUTC(ty, tm, td, -1);
  const periodFrom = new Date(Date.UTC(fy, fm, fd));
  return { periodFrom, periodTo };
}
