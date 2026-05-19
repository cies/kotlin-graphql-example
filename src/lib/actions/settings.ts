"use server";

import { prisma } from "@/lib/db/prisma";
import { auth } from "@/lib/auth/auth";
import { revalidatePath, revalidateTag } from "next/cache";
import { orgSettingsCacheTag } from "@/lib/settings/cached-org-settings";
import { clearLogoCacheForOrg } from "@/lib/branding/logo-png";
import { clearStripeCache } from "@/lib/stripe/client";
import { z } from "zod";
import { logAudit } from "@/lib/audit/log";
import { fetchEurLiveQuotes } from "@/lib/fx/fetch-eur-live-quotes";

const settingsSchema = z.object({
  displayCurrency: z.enum(["EUR", "USD", "GBP", "CHF", "CAD", "AUD"]).optional(),
  numberFormatStyle: z.enum(["COMMA_DOT", "DOT_COMMA"]).optional(),
  overdueReminderDays: z.array(z.number().int().positive()).optional(),
  autoSendInvoice: z.boolean().optional(),
  vatRate: z.string().optional(),
  companyName: z.string().optional(),
  companyLogoUrl: z.string().url().optional().or(z.literal("")),
  companyAddress: z.string().optional(),
  companyCity: z.string().optional(),
  companyState: z.string().optional(),
  companyCountry: z.string().optional(),
  companyPostalCode: z.string().optional(),
  companyVat: z.string().optional(),
  companyPhone: z.string().optional(),
  defaultVatIncluded: z.boolean().optional(),
  defaultDueDays: z.number().int().min(0).nullable().optional(),
  // Numbering
  invoiceNumberPrefix: z.string().optional(),
  invoiceNumberFormat: z.enum(["YEAR_SEQ", "YEARMONTH_SEQ", "SEQ_ONLY", "DATE_RANDOM"]).optional(),
  invoiceNumberPadding: z.number().int().min(1).max(10).optional(),
  invoiceNumberRandomLength: z.number().int().min(1).max(32).optional(),
  receiptNumberPrefix: z.string().optional(),
  receiptNumberFormat: z.enum(["YEAR_SEQ", "YEARMONTH_SEQ", "SEQ_ONLY", "DATE_RANDOM"]).optional(),
  receiptNumberPadding: z.number().int().min(1).max(10).optional(),
  receiptNumberRandomLength: z.number().int().min(1).max(32).optional(),
  // Email
  emailDesign: z.enum(["MODERN_MINIMAL", "CLASSIC_PROFESSIONAL", "BRANDED_BOLD"]).optional(),
  emailAccentColor: z.string().regex(/^#[0-9a-fA-F]{3,8}$/).optional().or(z.literal("")),
  /** Single legal footer line (entity • address) shown at the bottom of designed emails */
  emailEnvelopeFooterLine: z.string().max(600).optional().nullable(),
  // Invoice templates + personalization
  defaultInvoiceTemplate: z.enum(["CLASSIC", "MODERN", "MINIMAL", "CORPORATE"]).optional(),
  invoicePdfFont: z
    .enum(["ROBOTO", "ARIAL", "HELVETICA", "GEIST", "INTER", "OPEN_SANS"])
    .optional(),
  showInvoiceStatus: z.boolean().optional(),
  invoiceAccentColor: z.string().regex(/^#[0-9a-fA-F]{3,8}$/).optional().or(z.literal("")),
  invoiceFooterText: z.string().optional(),
  // Bank / wire transfer
  bankName: z.string().optional(),
  bankIban: z.string().optional(),
  bankBic: z.string().optional(),
  bankAccountHolder: z.string().optional(),
  bankInstructions: z.string().optional(),
  // Privacy
  privacyGdpr: z.boolean().optional(),
  privacyCcpa: z.boolean().optional(),
  // Stripe
  stripeSecretKey: z.string().optional(),
  stripePublishableKey: z.string().optional(),
  stripeWebhookSecret: z.string().optional(),
  // SMTP
  smtpHost: z.string().optional(),
  smtpPort: z.number().int().optional(),
  smtpEncryption: z.enum(["TLS", "SSL", "NONE"]).optional(),
  smtpUser: z.string().optional(),
  smtpPass: z.string().optional(),
  smtpFrom: z.string().optional(),
  // Twilio
  twilioSid: z.string().optional(),
  twilioToken: z.string().optional(),
  twilioFrom: z.string().optional(),
  // Time tracking
  timeRounding: z.enum(["NONE", "UP_30", "UP_60", "DOWN_30", "DOWN_60"]).optional(),
  // Default auto-invoice billing schedule
  defaultAutoInvoiceCycle: z.enum(["WEEKLY", "BIWEEKLY", "MONTHLY", "SEMI_MONTHLY"]).optional(),
  semiMonthlyPeriodSplitDay: z.number().int().min(1).max(28).optional(),
  semiMonthlyEmitDay1: z.number().int().min(1).max(31).optional(),
  semiMonthlyEmitDay2: z.number().int().min(1).max(31).optional(),
  trackInvoiceEmailOpens: z.boolean().optional(),
})
  .superRefine((val, ctx) => {
    const checks: { prefix?: string; fmt?: string; label: string }[] = [
      { prefix: val.invoiceNumberPrefix, fmt: val.invoiceNumberFormat, label: "Invoice number prefix" },
      { prefix: val.receiptNumberPrefix, fmt: val.receiptNumberFormat, label: "Receipt number prefix" },
    ];
    for (const { prefix, fmt, label } of checks) {
      if (prefix && fmt && /\{RANDOM\}/i.test(prefix) && fmt !== "DATE_RANDOM") {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: `${label}: placeholder {RANDOM} is only allowed when the format is PREFIX-YYYYMMDD-RANDOM.`,
        });
      }
    }
  });

export type SettingsInput = z.infer<typeof settingsSchema>;

async function getOrgId(orgSlug: string): Promise<string | null> {
  const session = await auth();
  if (!session?.user || session.user.userType !== "STAFF") return null;

  const org = await prisma.organization.findUnique({
    where: { slug: orgSlug },
    select: { id: true },
  });

  if (!org) return null;

  const member = await prisma.organizationMember.findUnique({
    where: {
      organizationId_userId: { organizationId: org.id, userId: session.user.id },
    },
  });

  return member ? org.id : null;
}

export async function updateOrgSettings(orgSlug: string, input: SettingsInput) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  const parsed = settingsSchema.safeParse(input);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  const SECRET_FIELDS = ["smtpPass", "stripeSecretKey", "stripeWebhookSecret", "twilioToken"] as const;
  const OMIT_EMPTY_PRESERVE_EXISTING = [...SECRET_FIELDS, "stripePublishableKey"] as const;

  const data = Object.fromEntries(
    Object.entries({
      ...parsed.data,
      companyLogoUrl: parsed.data.companyLogoUrl || null,
      vatRate: parsed.data.vatRate ? parsed.data.vatRate : undefined,
      ...(Object.hasOwn(input as Record<string, unknown>, "invoiceAccentColor")
        ? {
            invoiceAccentColor:
              parsed.data.invoiceAccentColor && parsed.data.invoiceAccentColor.trim() !== ""
                ? parsed.data.invoiceAccentColor
                : null,
          }
        : {}),
    }).filter(([key, value]) => {
      // Never overwrite stored credentials / publishable key with empty - omit the field instead
      if (OMIT_EMPTY_PRESERVE_EXISTING.includes(key as typeof OMIT_EMPTY_PRESERVE_EXISTING[number])) {
        return value !== "" && value !== undefined && value !== null;
      }
      return true;
    })
  );
  const orgSettingsFieldNames =
    ((prisma as { _runtimeDataModel?: { models?: { OrgSettings?: { fields?: { name?: string }[] } } } })
      ._runtimeDataModel?.models?.OrgSettings?.fields ?? []
    )
      .map((f) => f?.name)
      .filter((name): name is string => typeof name === "string");
  const runtimeFieldSet = new Set(orgSettingsFieldNames);
  const filteredData = Object.fromEntries(
    Object.entries(data).filter(([key]) => runtimeFieldSet.has(key))
  );
  const requestedTimeRounding =
    typeof (data as Record<string, unknown>).timeRounding === "string"
      ? ((data as Record<string, unknown>).timeRounding as string)
      : null;
  const requestedNumberFormatStyle =
    typeof (data as Record<string, unknown>).numberFormatStyle === "string"
      ? ((data as Record<string, unknown>).numberFormatStyle as string)
      : null;
  const requestedShowInvoiceStatus =
    typeof (data as Record<string, unknown>).showInvoiceStatus === "boolean"
      ? ((data as Record<string, unknown>).showInvoiceStatus as boolean)
      : null;
  const requestedInvoicePdfFont =
    typeof (data as Record<string, unknown>).invoicePdfFont === "string"
      ? ((data as Record<string, unknown>).invoicePdfFont as string)
      : null;

  await prisma.orgSettings.upsert({
    where: { organizationId: orgId },
    update: filteredData,
    create: { ...filteredData, organizationId: orgId },
  });

  if ("stripeSecretKey" in filteredData) {
    clearStripeCache(orgId);
  }

  if (requestedTimeRounding && !runtimeFieldSet.has("timeRounding")) {
    await prisma.$executeRaw`
      UPDATE "OrgSettings"
      SET "timeRounding" = ${requestedTimeRounding}::"TimeRounding"
      WHERE "organizationId" = ${orgId}
    `;
  }
  if (requestedNumberFormatStyle && !runtimeFieldSet.has("numberFormatStyle")) {
    await prisma.$executeRaw`
      UPDATE "OrgSettings"
      SET "numberFormatStyle" = ${requestedNumberFormatStyle}
      WHERE "organizationId" = ${orgId}
    `;
  }
  if (requestedShowInvoiceStatus !== null && !runtimeFieldSet.has("showInvoiceStatus")) {
    await prisma.$executeRaw`
      UPDATE "OrgSettings"
      SET "showInvoiceStatus" = ${requestedShowInvoiceStatus}
      WHERE "organizationId" = ${orgId}
    `;
  }
  const INVOICE_PDF_FONT_VALUES = ["ROBOTO", "ARIAL", "HELVETICA", "GEIST", "INTER", "OPEN_SANS"] as const;
  if (
    requestedInvoicePdfFont &&
    INVOICE_PDF_FONT_VALUES.includes(requestedInvoicePdfFont as (typeof INVOICE_PDF_FONT_VALUES)[number]) &&
    !runtimeFieldSet.has("invoicePdfFont")
  ) {
    await prisma.$executeRaw`
      UPDATE "OrgSettings"
      SET "invoicePdfFont" = ${requestedInvoicePdfFont}::"InvoicePdfFont"
      WHERE "organizationId" = ${orgId}
    `;
  }

  // Redact secret values in audit metadata
  const safeKeys = Object.keys(filteredData).filter((k) => {
    return ![
      "stripeSecretKey",
      "stripeWebhookSecret",
      "stripePublishableKey",
      "smtpPass",
      "twilioToken",
    ].includes(k);
  });
  await logAudit({
    organizationId: orgId,
    action: "UPDATE",
    entityType: "SETTINGS",
    entityId: orgId,
    metadata: { fieldsUpdated: safeKeys },
  });

  revalidateTag(orgSettingsCacheTag(orgId), "default");
  if (Object.hasOwn(filteredData as Record<string, unknown>, "companyLogoUrl")) {
    clearLogoCacheForOrg(orgId);
  }

  revalidatePath(`/${orgSlug}/settings`);
  return { success: true };
}

export async function syncFxRates(orgSlug: string) {
  const orgId = await getOrgId(orgSlug);
  if (!orgId) return { error: "Unauthorized" };

  try {
    const currencies = ["USD", "GBP", "CHF", "CAD", "AUD"] as const;
    const quotes = await fetchEurLiveQuotes();

    if (!quotes) throw new Error("FX API error");

    await Promise.all(
      currencies.map((currency) => {
        const rate = quotes[`EUR${currency}`];
        if (!rate) return;
        return prisma.fxRate.upsert({
          where: {
            organizationId_baseCurrency_quoteCurrency: {
              organizationId: orgId,
              baseCurrency: "EUR",
              quoteCurrency: currency,
            },
          },
          update: { rate, fetchedAt: new Date() },
          create: {
            organizationId: orgId,
            baseCurrency: "EUR",
            quoteCurrency: currency,
            rate,
          },
        });
      })
    );

    revalidatePath(`/${orgSlug}/settings`);
    return { success: true };
  } catch (error) {
    return { error: "Failed to fetch FX rates. Check network or try again later." };
  }
}
