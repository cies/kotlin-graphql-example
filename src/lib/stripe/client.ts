import Stripe from "stripe";
import { prisma } from "@/lib/db/prisma";

const stripeClients = new Map<string, Stripe>();

export async function stripeClient(orgId: string): Promise<Stripe | null> {
  if (stripeClients.has(orgId)) return stripeClients.get(orgId)!;

  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: { stripeSecretKey: true },
  });

  if (!settings?.stripeSecretKey) return null;

  const client = new Stripe(settings.stripeSecretKey, {
    apiVersion: "2026-04-22.dahlia",
  });

  stripeClients.set(orgId, client);
  return client;
}

export function clearStripeCache(orgId: string) {
  stripeClients.delete(orgId);
}
