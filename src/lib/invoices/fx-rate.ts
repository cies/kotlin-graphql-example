import { prisma } from "@/lib/db/prisma";
import type { Currency } from "@prisma/client";

export async function getFxRate(orgId: string, currency: Currency): Promise<number | null> {
  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: { displayCurrency: true },
  });
  if (!settings || settings.displayCurrency === currency) return 1;

  const rate = await prisma.fxRate.findUnique({
    where: {
      organizationId_baseCurrency_quoteCurrency: {
        organizationId: orgId,
        baseCurrency: settings.displayCurrency,
        quoteCurrency: currency,
      },
    },
  });

  return rate ? parseFloat(rate.rate.toString()) : null;
}
