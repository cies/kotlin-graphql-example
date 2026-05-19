/** Default Corporate accent when org has no custom `invoiceAccentColor` (used for docs / parity with PDF defaults). */
export const CORPORATE_DEFAULT_ACCENT = "#1d4ed8";

const DATES_BG = "#eff6ff";
const DATES_BORDER = "#bfdbfe";
const NEUTRAL = "#e5e7eb";

const WHITE = { r: 255, g: 255, b: 255 };

export type CorporateChrome = {
  datesBg: string;
  datesBorder: string;
  datesSep: string;
  tableHeaderBg: string;
  tableRowBorder: string;
  tableRowAltBg: string;
  headerCellText: string;
  totalsBorder: string;
  totalRowBorder: string;
  grandBg: string;
  footerBg: string;
};

function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
  const t = hex.trim();
  let m = /^#([0-9a-f]{3})$/i.exec(t);
  if (m) {
    const [a, b, c] = m[1]!.split("");
    return {
      r: parseInt(a! + a!, 16),
      g: parseInt(b! + b!, 16),
      b: parseInt(c! + c!, 16),
    };
  }
  m = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(t);
  if (m) {
    const s = m[1]!;
    return { r: parseInt(s.slice(0, 2), 16), g: parseInt(s.slice(2, 4), 16), b: parseInt(s.slice(4, 6), 16) };
  }
  return null;
}

function mixRgb(from: { r: number; g: number; b: number }, to: { r: number; g: number; b: number }, t: number) {
  return {
    r: Math.round(from.r + (to.r - from.r) * t),
    g: Math.round(from.g + (to.g - from.g) * t),
    b: Math.round(from.b + (to.b - from.b) * t),
  };
}

function rgbToHex(rgb: { r: number; g: number; b: number }): string {
  const clamp = (n: number) => Math.min(255, Math.max(0, n));
  return `#${[rgb.r, rgb.g, rgb.b].map((x) => clamp(x).toString(16).padStart(2, "0")).join("")}`;
}

/**
 * Solid hex equivalent to laying `accentRgb` on white at fraction `amount`.
 * Use for borders and fills: @react-pdf/renderer often renders `rgba(...)` stroke colors incorrectly (e.g. yellow).
 */
function tintOnWhite(accentRgb: { r: number; g: number; b: number }, amount: number): string {
  return rgbToHex(mixRgb(WHITE, accentRgb, Math.min(1, Math.max(0, amount))));
}

const NEAR_BLACK = { r: 3, g: 7, b: 18 };
const SLATE_TEXT = { r: 15, g: 23, b: 42 };

const DEFAULT_CHROME: CorporateChrome = {
  datesBg: DATES_BG,
  datesBorder: DATES_BORDER,
  datesSep: DATES_BORDER,
  tableHeaderBg: "#dbeafe",
  tableRowBorder: "#f0f4ff",
  tableRowAltBg: "#f8faff",
  headerCellText: "#1e3a8a",
  totalsBorder: NEUTRAL,
  totalRowBorder: DATES_BG,
  grandBg: "#1e3a8a",
  footerBg: "#1e3a8a",
};

/**
 * When `accentColor` is unset, use fixed blue-grey Corporate defaults.
 * When set to a valid hex, derive light tints (solid hex mixes toward white) and dark mixes so the PDF stays in one hue family.
 */
export function resolveCorporateChrome(accentColor: string | null | undefined): CorporateChrome {
  const raw = accentColor?.trim();
  if (!raw) return { ...DEFAULT_CHROME };

  const rgb = hexToRgb(raw);
  if (!rgb) return { ...DEFAULT_CHROME };

  return {
    datesBg: tintOnWhite(rgb, 0.11),
    datesBorder: tintOnWhite(rgb, 0.34),
    datesSep: tintOnWhite(rgb, 0.38),
    tableHeaderBg: tintOnWhite(rgb, 0.14),
    tableRowBorder: tintOnWhite(rgb, 0.1),
    tableRowAltBg: tintOnWhite(rgb, 0.06),
    headerCellText: rgbToHex(mixRgb(rgb, SLATE_TEXT, 0.52)),
    totalsBorder: tintOnWhite(rgb, 0.32),
    totalRowBorder: tintOnWhite(rgb, 0.1),
    grandBg: rgbToHex(mixRgb(rgb, NEAR_BLACK, 0.62)),
    footerBg: rgbToHex(mixRgb(rgb, NEAR_BLACK, 0.62)),
  };
}
