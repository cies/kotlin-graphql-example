/**
 * Unified email composer.
 * Calls renderTemplate for inner HTML, wraps it with the org's chosen design,
 * and fetches + converts the org logo to PNG for CID embedding.
 */
import { prisma } from "@/lib/db/prisma";
import { renderTemplate } from "./templates";
import { getDesign } from "./designs";
import { fetchAndConvertLogo } from "@/lib/branding/logo-png";
import type { EmailDesign } from "@prisma/client";

export interface ComposedEmail {
  subject: string;
  html: string;
  attachments: Array<{
    filename: string;
    content: Buffer;
    contentType: string;
    cid?: string;
  }>;
}

import { computeEmailFooterLegalLine } from "./footer-legal-line";

interface OrgBranding {
  companyName: string;
  companyLogoUrl: string | null;
  companyAddress: string | null;
  companyCity: string | null;
  companyState: string | null;
  companyPostalCode: string | null;
  companyCountry: string | null;
  emailEnvelopeFooterLine: string | null;
  emailDesign: EmailDesign;
  emailAccentColor: string | null;
}

async function getOrgBranding(orgId: string): Promise<OrgBranding> {
  const settings = await prisma.orgSettings.findUnique({
    where: { organizationId: orgId },
    select: {
      companyName: true,
      companyLogoUrl: true,
      companyAddress: true,
      companyCity: true,
      companyState: true,
      companyPostalCode: true,
      companyCountry: true,
      emailEnvelopeFooterLine: true,
      emailDesign: true,
      emailAccentColor: true,
    },
  });

  const org = await prisma.organization.findUnique({
    where: { id: orgId },
    select: { name: true },
  });

  return {
    companyName: settings?.companyName || org?.name || "Your Company",
    companyLogoUrl: settings?.companyLogoUrl ?? null,
    companyAddress: settings?.companyAddress ?? null,
    companyCity: settings?.companyCity ?? null,
    companyState: settings?.companyState ?? null,
    companyPostalCode: settings?.companyPostalCode ?? null,
    companyCountry: settings?.companyCountry ?? null,
    emailEnvelopeFooterLine: settings?.emailEnvelopeFooterLine ?? null,
    emailDesign: settings?.emailDesign ?? "MODERN_MINIMAL",
    emailAccentColor: settings?.emailAccentColor ?? null,
  };
}

/**
 * Composes a fully-designed email for sending.
 * `extraAttachments` lets callers pass e.g. PDF invoice attachments.
 */
export async function composeEmail(
  orgId: string,
  key: string,
  vars: Record<string, string>,
  extraAttachments?: Array<{ filename: string; content: Buffer; contentType: string }>
): Promise<ComposedEmail> {
  const [branding, { subject, html: innerHtml }] = await Promise.all([
    getOrgBranding(orgId),
    renderTemplate(orgId, key, vars),
  ]);

  const wrapFn = getDesign(branding.emailDesign);

  const attachments: ComposedEmail["attachments"] = [];
  let hasLogo = false;

  if (branding.companyLogoUrl) {
    try {
      const pngBuf = await fetchAndConvertLogo(branding.companyLogoUrl, orgId);
      attachments.push({
        filename: "logo.png",
        content: pngBuf,
        contentType: "image/png",
        cid: "logo@crm",
      });
      hasLogo = true;
    } catch {
      // Logo fetch failed - fall back to text
    }
  }

  const html = wrapFn(innerHtml, {
    companyName: branding.companyName,
    footerLegalLine: computeEmailFooterLegalLine({
      envelopeFooterLine: branding.emailEnvelopeFooterLine,
      companyName: branding.companyName,
      companyAddress: branding.companyAddress,
      companyCity: branding.companyCity,
      companyState: branding.companyState,
      companyPostalCode: branding.companyPostalCode,
      companyCountry: branding.companyCountry,
    }),
    accentColor: branding.emailAccentColor ?? "",
    hasLogo,
    footerHtml: "",
  });

  if (extraAttachments) {
    attachments.push(...extraAttachments);
  }

  return { subject, html, attachments };
}

/**
 * Like composeEmail but accepts raw subject/body overrides (for unsaved preview/test).
 */
export async function composeEmailWithOverrides(
  orgId: string,
  key: string,
  vars: Record<string, string>,
  subjectOverride: string,
  bodyOverride: string,
  extraAttachments?: Array<{ filename: string; content: Buffer; contentType: string }>
): Promise<ComposedEmail> {
  const branding = await getOrgBranding(orgId);
  const wrapFn = getDesign(branding.emailDesign);

  // Merge vars into the overrides
  let subject = subjectOverride;
  let innerHtml = bodyOverride;
  for (const [k, v] of Object.entries(vars)) {
    const pat = new RegExp(`\\{\\{${k.replace(/\./g, "\\.")}\\}\\}`, "g");
    subject = subject.replace(pat, v ?? "");
    innerHtml = innerHtml.replace(pat, v ?? "");
  }
  subject = subject.replace(/\{\{[^}]+\}\}/g, "");
  innerHtml = innerHtml.replace(/\{\{[^}]+\}\}/g, "");

  const attachments: ComposedEmail["attachments"] = [];
  let hasLogo = false;

  if (branding.companyLogoUrl) {
    try {
      const pngBuf = await fetchAndConvertLogo(branding.companyLogoUrl, orgId);
      attachments.push({
        filename: "logo.png",
        content: pngBuf,
        contentType: "image/png",
        cid: "logo@crm",
      });
      hasLogo = true;
    } catch {
      // fallback
    }
  }

  const html = wrapFn(innerHtml, {
    companyName: branding.companyName,
    footerLegalLine: computeEmailFooterLegalLine({
      envelopeFooterLine: branding.emailEnvelopeFooterLine,
      companyName: branding.companyName,
      companyAddress: branding.companyAddress,
      companyCity: branding.companyCity,
      companyState: branding.companyState,
      companyPostalCode: branding.companyPostalCode,
      companyCountry: branding.companyCountry,
    }),
    accentColor: branding.emailAccentColor ?? "",
    hasLogo,
    footerHtml: "",
  });

  if (extraAttachments) {
    attachments.push(...extraAttachments);
  }

  return { subject, html, attachments };
}
