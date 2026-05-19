import "server-only";

import { resolveAbsoluteAppUrl } from "@/lib/env/resolve-public-app-url.server";

export async function resolveInvoicePublicUrl(token: string): Promise<string> {
  return resolveAbsoluteAppUrl(`/invoice/${token}`);
}
