/**
 * Tiny CSV serialiser. We avoid pulling in a dependency for this - the
 * generated CSVs only need to be Excel/Numbers/LibreOffice friendly.
 */

const RFC4180_NEEDS_QUOTING = /[",\r\n]/;

export type CsvRow = Record<string, string | number | boolean | Date | null | undefined>;

export interface CsvColumn<T extends CsvRow = CsvRow> {
  key: keyof T & string;
  label: string;
}

function escapeCell(value: unknown): string {
  if (value === null || value === undefined) return "";

  let str: string;
  if (value instanceof Date) {
    str = value.toISOString();
  } else if (typeof value === "boolean") {
    str = value ? "true" : "false";
  } else {
    str = String(value);
  }

  if (RFC4180_NEEDS_QUOTING.test(str)) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
}

export function rowsToCsv<T extends CsvRow>(
  rows: T[],
  columns: CsvColumn<T>[]
): string {
  const header = columns.map((c) => escapeCell(c.label)).join(",");
  const body = rows
    .map((row) => columns.map((col) => escapeCell(row[col.key])).join(","))
    .join("\r\n");
  return `${header}\r\n${body}`;
}

/**
 * Returns a Response that streams CSV with the right headers so the
 * browser opens a download dialog.
 */
export function csvResponse(filename: string, csv: string): Response {
  // Add a UTF-8 BOM so Excel recognises encoding correctly
  const body = "\ufeff" + csv;
  return new Response(body, {
    status: 200,
    headers: {
      "Content-Type": "text/csv; charset=utf-8",
      "Content-Disposition": `attachment; filename="${filename}"`,
      "Cache-Control": "no-store",
    },
  });
}
