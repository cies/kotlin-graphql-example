import "server-only";

import { resolvePublicAppOrigin } from "@/lib/env/resolve-public-app-url.server";
import {
  appendInvoiceOpenPixelWithBase,
  getEmailAppOrigin,
} from "@/lib/invoice/email-open-pixel";

/** Server Actions: uses request Host when env is unset (workers use sync helper + env only). */
export async function appendInvoiceEmailOpenPixelAsync(
  html: string,
  sendId: string
): Promise<string> {
  const origin = (await resolvePublicAppOrigin()) || getEmailAppOrigin();
  return appendInvoiceOpenPixelWithBase(html, sendId, origin);
}
