/**
 * Strips credentials from URLs before logging (e.g. redis://:secret@host → redis://:***@host).
 */
export function redactConnectionUrlForLog(urlStr: string): string {
  try {
    const u = new URL(urlStr);
    if (u.password) u.password = "***";
    if (u.username) u.username = "***";
    return u.toString();
  } catch {
    return urlStr.includes("@") ? "[redacted-connection-url]" : urlStr;
  }
}
