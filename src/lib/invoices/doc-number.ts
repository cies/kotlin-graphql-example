import { prisma } from "@/lib/db/prisma";
import {
  expandDocNumberPrefix,
  hasDocNumberTemplateTokens,
  prefixContainsRandomToken,
  randomAlphanumericSegment,
} from "@/lib/invoices/number-template";

function legacyDateRandom(
  prefix: string,
  year: string,
  month: string,
  day: string,
  randomLen: number
): string {
  return `${prefix}-${year}${month}${day}-${randomAlphanumericSegment(randomLen)}`;
}

export async function generateDocNumber(
  orgId: string,
  entity: "invoice" | "receipt",
  settings: {
    prefix: string;
    format: string;
    padding: number;
    randomLength: number;
  }
): Promise<string> {
  const { prefix, format, padding, randomLength } = settings;
  const now = new Date();
  const year = String(now.getFullYear());
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  const len = Math.min(32, Math.max(1, randomLength));

  const useTemplates = hasDocNumberTemplateTokens(prefix);

  if (format === "DATE_RANDOM") {
    if (useTemplates) {
      if (prefixContainsRandomToken(prefix)) {
        return expandDocNumberPrefix(prefix, now, len);
      }
      const body = expandDocNumberPrefix(prefix, now, len, "");
      return `${body}-${randomAlphanumericSegment(len)}`;
    }
    return legacyDateRandom(prefix, year, month, day, len);
  }

  let searchPrefix: string;
  if (!useTemplates) {
    if (format === "YEAR_SEQ") searchPrefix = `${prefix}-${year}-`;
    else if (format === "YEARMONTH_SEQ") searchPrefix = `${prefix}-${year}${month}-`;
    else searchPrefix = `${prefix}-`;
  } else {
    const base = expandDocNumberPrefix(prefix, now, len, "");
    searchPrefix = `${base}-`;
  }

  let lastSeq = 0;
  if (entity === "invoice") {
    const last = await prisma.invoice.findFirst({
      where: { organizationId: orgId, number: { startsWith: searchPrefix } },
      orderBy: { number: "desc" },
      select: { number: true },
    });
    if (last) {
      const parts = last.number.split("-");
      const n = parseInt(parts[parts.length - 1] ?? "0");
      if (!isNaN(n)) lastSeq = n;
    }
  } else {
    const last = await prisma.payment.findFirst({
      where: { organizationId: orgId, receiptNumber: { startsWith: searchPrefix } },
      orderBy: { receiptNumber: "desc" },
      select: { receiptNumber: true },
    });
    if (last?.receiptNumber) {
      const parts = last.receiptNumber.split("-");
      const n = parseInt(parts[parts.length - 1] ?? "0");
      if (!isNaN(n)) lastSeq = n;
    }
  }

  const seq = String(lastSeq + 1).padStart(padding, "0");
  return `${searchPrefix}${seq}`;
}

export type NextInvoiceNumberOptions = {
  /** Replaces the organisation default prefix pattern for this allocation only (create / duplicate). */
  prefixOverride?: string | null;
};

export async function getNextInvoiceNumber(
  orgId: string,
  options?: NextInvoiceNumberOptions
): Promise<string> {
  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: {
      invoiceNumberPrefix: true,
      invoiceNumberFormat: true,
      invoiceNumberPadding: true,
      invoiceNumberRandomLength: true,
    },
  });
  const trim = options?.prefixOverride?.trim();
  const prefix =
    trim && trim.length > 0 ? trim : settings?.invoiceNumberPrefix ?? "INV";
  return generateDocNumber(orgId, "invoice", {
    prefix,
    format: settings?.invoiceNumberFormat ?? "YEAR_SEQ",
    padding: settings?.invoiceNumberPadding ?? 4,
    randomLength: settings?.invoiceNumberRandomLength ?? 6,
  });
}

export async function getNextReceiptNumber(orgId: string): Promise<string> {
  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: {
      receiptNumberPrefix: true,
      receiptNumberFormat: true,
      receiptNumberPadding: true,
      receiptNumberRandomLength: true,
    },
  });
  return generateDocNumber(orgId, "receipt", {
    prefix: settings?.receiptNumberPrefix ?? "REC",
    format: settings?.receiptNumberFormat ?? "YEAR_SEQ",
    padding: settings?.receiptNumberPadding ?? 4,
    randomLength: settings?.receiptNumberRandomLength ?? 6,
  });
}
