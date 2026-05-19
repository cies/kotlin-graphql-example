import type {
  Currency,
  OrgSettings,
  FxRate,
  SmtpEncryption,
  EmailDesign,
  InvoiceTemplate,
  TimeRounding,
  AutoInvoiceCycle,
  InvoicePdfFont,
} from "@prisma/client";
import { normalizeNumberFormatStyle, type NumberFormatStyle } from "@/lib/utils/format";

/** JSON-safe props for client `OrgSettingsForm` (no Prisma Decimal / Date). */
export type SerializedOrgSettings = {
  displayCurrency: Currency;
  numberFormatStyle: NumberFormatStyle;
  autoSendInvoice: boolean;
  vatRate: string;
  companyName: string | null;
  companyAddress: string | null;
  companyCity: string | null;
  companyState: string | null;
  companyCountry: string | null;
  companyPostalCode: string | null;
  companyVat: string | null;
  companyPhone: string | null;
  companyLogoUrl: string | null;
  // Invoice defaults
  defaultVatIncluded: boolean;
  defaultDueDays: number | null;
  // Numbering
  invoiceNumberPrefix: string;
  invoiceNumberFormat: string;
  invoiceNumberPadding: number;
  invoiceNumberRandomLength: number;
  receiptNumberPrefix: string;
  receiptNumberFormat: string;
  receiptNumberPadding: number;
  receiptNumberRandomLength: number;
  // Email
  emailDesign: EmailDesign;
  emailAccentColor: string | null;
  emailEnvelopeFooterLine: string | null;
  // Invoice
  defaultInvoiceTemplate: InvoiceTemplate;
  invoicePdfFont: InvoicePdfFont;
  showInvoiceStatus: boolean;
  invoiceAccentColor: string | null;
  invoiceFooterText: string | null;
  // Bank
  bankName: string | null;
  bankIban: string | null;
  bankBic: string | null;
  bankAccountHolder: string | null;
  bankInstructions: string | null;
  // Privacy
  privacyGdpr: boolean;
  privacyCcpa: boolean;
  // SMTP
  smtpHost: string | null;
  smtpPort: number | null;
  smtpEncryption: SmtpEncryption;
  smtpUser: string | null;
  smtpFrom: string | null;
  /** Stored publishable key is not sent to the browser; check flags + clear field to preserve. */
  credentialFlags: {
    stripePublishableKeySet: boolean;
    stripeSecretKeySet: boolean;
    stripeWebhookSecretSet: boolean;
    smtpPasswordSet: boolean;
  };
  // Time tracking
  timeRounding: TimeRounding;
  // Default auto-invoice billing schedule
  defaultAutoInvoiceCycle: AutoInvoiceCycle;
  semiMonthlyPeriodSplitDay: number;
  semiMonthlyEmitDay1: number;
  semiMonthlyEmitDay2: number;
  trackInvoiceEmailOpens: boolean;
};

export function serializeOrgSettings(settings: OrgSettings | null): SerializedOrgSettings | null {
  if (!settings) return null;
  const numberFormatStyle = normalizeNumberFormatStyle(
    (settings as unknown as { numberFormatStyle?: string | null }).numberFormatStyle
  );
  return {
    displayCurrency: settings.displayCurrency,
    numberFormatStyle,
    autoSendInvoice: settings.autoSendInvoice,
    vatRate: settings.vatRate.toString(),
    companyName: settings.companyName,
    companyAddress: settings.companyAddress,
    companyCity: settings.companyCity,
    companyState: settings.companyState,
    companyCountry: settings.companyCountry,
    companyPostalCode: settings.companyPostalCode,
    companyVat: settings.companyVat,
    companyPhone: settings.companyPhone,
    companyLogoUrl: settings.companyLogoUrl,
    defaultVatIncluded: settings.defaultVatIncluded,
    defaultDueDays: settings.defaultDueDays,
    invoiceNumberPrefix: settings.invoiceNumberPrefix,
    invoiceNumberFormat: settings.invoiceNumberFormat,
    invoiceNumberPadding: settings.invoiceNumberPadding,
    invoiceNumberRandomLength: settings.invoiceNumberRandomLength,
    receiptNumberPrefix: settings.receiptNumberPrefix,
    receiptNumberFormat: settings.receiptNumberFormat,
    receiptNumberPadding: settings.receiptNumberPadding,
    receiptNumberRandomLength: settings.receiptNumberRandomLength,
    emailDesign: settings.emailDesign,
    emailAccentColor: settings.emailAccentColor,
    emailEnvelopeFooterLine: settings.emailEnvelopeFooterLine ?? null,
    defaultInvoiceTemplate: settings.defaultInvoiceTemplate,
    invoicePdfFont: settings.invoicePdfFont,
    showInvoiceStatus: (settings as unknown as { showInvoiceStatus?: boolean }).showInvoiceStatus ?? true,
    invoiceAccentColor: settings.invoiceAccentColor,
    invoiceFooterText: settings.invoiceFooterText,
    bankName: settings.bankName,
    bankIban: settings.bankIban,
    bankBic: settings.bankBic,
    bankAccountHolder: settings.bankAccountHolder,
    bankInstructions: settings.bankInstructions,
    privacyGdpr: settings.privacyGdpr,
    privacyCcpa: settings.privacyCcpa,
    smtpHost: settings.smtpHost,
    smtpPort: settings.smtpPort,
    smtpEncryption: settings.smtpEncryption,
    smtpUser: settings.smtpUser,
    smtpFrom: settings.smtpFrom,
    credentialFlags: {
      stripePublishableKeySet: !!settings.stripePublishableKey?.trim(),
      stripeSecretKeySet: !!settings.stripeSecretKey?.trim(),
      stripeWebhookSecretSet: !!settings.stripeWebhookSecret?.trim(),
      smtpPasswordSet: !!settings.smtpPass?.trim(),
    },
    timeRounding: settings.timeRounding,
    defaultAutoInvoiceCycle: settings.defaultAutoInvoiceCycle,
    semiMonthlyPeriodSplitDay: settings.semiMonthlyPeriodSplitDay,
    semiMonthlyEmitDay1: settings.semiMonthlyEmitDay1,
    semiMonthlyEmitDay2: settings.semiMonthlyEmitDay2,
    trackInvoiceEmailOpens: settings.trackInvoiceEmailOpens ?? true,
  };
}

export type SerializedFxRate = {
  quoteCurrency: Currency;
  rate: string;
  fetchedAt: string;
};

export function serializeFxRates(rates: FxRate[]): SerializedFxRate[] {
  return rates.map((r) => ({
    quoteCurrency: r.quoteCurrency,
    rate: r.rate.toString(),
    fetchedAt: r.fetchedAt.toISOString(),
  }));
}
