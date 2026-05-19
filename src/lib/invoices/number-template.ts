/**
 * Pure helpers for invoice/receipt number patterns.
 * Prefix may include tokens: {YYYY}, {MM}, {DD}, {RANDOM} (case-insensitive inside braces).
 */

const TOKEN_RE = /\{(YYYY|MM|DD|RANDOM)\}/i;

export function hasDocNumberTemplateTokens(prefix: string): boolean {
  return TOKEN_RE.test(prefix);
}

export function prefixContainsRandomToken(prefix: string): boolean {
  return /\{RANDOM\}/i.test(prefix);
}

function expandDateParts(template: string, d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return template.replace(/\{YYYY\}/gi, String(y)).replace(/\{MM\}/gi, m).replace(/\{DD\}/gi, day);
}

/** Cryptographically suitable alphanumeric (no ambiguous I/O/0/1 in default alphabet). */
export function randomAlphanumericSegment(length: number): string {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  let out = "";
  for (let i = 0; i < length; i++) {
    out += chars[bytes[i]! % chars.length]!;
  }
  return out;
}

/**
 * Expands all tokens. Each `{RANDOM}` becomes the same segment (length `randomLen`).
 * For preview, pass `randomPlaceholder` instead of generating random bytes.
 */
export function expandDocNumberPrefix(
  template: string,
  d: Date,
  randomLen: number,
  randomPlaceholder?: string
): string {
  if (!/\{RANDOM\}/i.test(template)) {
    return expandDateParts(template, d);
  }
  const seg =
    randomPlaceholder !== undefined
      ? randomPlaceholder
      : randomLen > 0
        ? randomAlphanumericSegment(randomLen)
        : "";
  const withDates = expandDateParts(template, d);
  return withDates.replace(/\{RANDOM\}/gi, () => seg);
}
