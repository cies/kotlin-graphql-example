import type { EmailDesign } from "@prisma/client";
import { wrap as wrapModernMinimal } from "./modern-minimal";
import { wrap as wrapClassicProfessional } from "./classic-professional";
import { wrap as wrapBrandedBold } from "./branded-bold";

export interface BrandCtx {
  companyName: string;
  /** Single line (entity • address) shown in the email footer band. */
  footerLegalLine: string;
  accentColor: string;
  hasLogo: boolean;
  footerHtml: string;
}

export type DesignWrapFn = (inner: string, brand: BrandCtx) => string;

const DESIGNS: Record<EmailDesign, DesignWrapFn> = {
  MODERN_MINIMAL: wrapModernMinimal,
  CLASSIC_PROFESSIONAL: wrapClassicProfessional,
  BRANDED_BOLD: wrapBrandedBold,
};

export function getDesign(design: EmailDesign): DesignWrapFn {
  return DESIGNS[design] ?? wrapModernMinimal;
}
