import { getPublicAppOriginFromEnv } from "@/lib/env/public-app-url";

/** Origin for email embeds when env is set (workers / background send). */
export function getEmailAppOrigin(): string {
  const fromEnv = getPublicAppOriginFromEnv();
  if (fromEnv) return fromEnv;
  if (process.env.NODE_ENV !== "production") {
    return "http://localhost:3000";
  }
  return "";
}

export function appendInvoiceOpenPixelWithBase(
  html: string,
  sendId: string,
  origin: string
): string {
  if (!origin) return html;
  const src = `${origin.replace(/\/+$/, "")}/api/track/invoice-email/${sendId}`;
  const pixel = `<img src="${src}" width="1" height="1" alt="" style="display:block;width:1px;height:1px;border:0" />`;
  if (html.includes("</body>")) {
    return html.replace("</body>", `${pixel}</body>`);
  }
  return `${html}${pixel}`;
}

/** Appends a 1×1 tracking image; opens are recorded when the client loads images. */
export function appendInvoiceEmailOpenPixel(html: string, sendId: string): string {
  return appendInvoiceOpenPixelWithBase(html, sendId, getEmailAppOrigin());
}
