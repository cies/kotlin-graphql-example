import fs from "fs";
import path from "path";
import type { InvoicePdfFont } from "@prisma/client";
import { Font } from "@react-pdf/renderer";

const registeredFamilies = new Set<string>();

function fontsourceFile(face: string, file: string): string {
  return path.join(process.cwd(), "node_modules", "@fontsource", face, "files", file);
}

function pkgGeist(...segments: string[]): string {
  return path.join(process.cwd(), "node_modules", "geist", ...segments);
}

/** Register 400 / 500 / 700 for Latin body + line-item medium + headings. */
function registerFamilyWeights(
  family: string,
  regularSrc: string,
  mediumSrc: string,
  boldSrc: string
): boolean {
  if (registeredFamilies.has(family)) return true;
  if (!fs.existsSync(regularSrc) || !fs.existsSync(mediumSrc) || !fs.existsSync(boldSrc)) return false;
  try {
    Font.register({
      family,
      fonts: [
        { src: regularSrc, fontWeight: "normal" },
        { src: mediumSrc, fontWeight: 500 },
        { src: boldSrc, fontWeight: "bold" },
      ],
    });
    registeredFamilies.add(family);
    return true;
  } catch {
    return false;
  }
}

function resolveArial(): { regular: string; bold: string } | null {
  const envR = process.env.ARIAL_FONT_REGULAR;
  const envB = process.env.ARIAL_FONT_BOLD;
  if (envR && envB && fs.existsSync(envR) && fs.existsSync(envB)) {
    return { regular: envR, bold: envB };
  }
  if (process.platform === "win32") {
    const dir = path.join(process.env.SystemRoot || "C:\\Windows", "Fonts");
    const regular = path.join(dir, "arial.ttf");
    const bold = path.join(dir, "arialbd.ttf");
    if (fs.existsSync(regular) && fs.existsSync(bold)) return { regular, bold };
  }
  const linuxPaths = [
    ["/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"],
    ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"],
  ];
  for (const [regular, bold] of linuxPaths) {
    if (fs.existsSync(regular) && fs.existsSync(bold)) return { regular, bold };
  }
  return null;
}

function registerRoboto(): boolean {
  return registerFamilyWeights(
    "Roboto",
    fontsourceFile("roboto", "roboto-latin-400-normal.woff"),
    fontsourceFile("roboto", "roboto-latin-500-normal.woff"),
    fontsourceFile("roboto", "roboto-latin-700-normal.woff")
  );
}

function registerInter(): boolean {
  return registerFamilyWeights(
    "Inter",
    fontsourceFile("inter", "inter-latin-400-normal.woff"),
    fontsourceFile("inter", "inter-latin-500-normal.woff"),
    fontsourceFile("inter", "inter-latin-700-normal.woff")
  );
}

function registerOpenSans(): boolean {
  return registerFamilyWeights(
    "Open Sans",
    fontsourceFile("open-sans", "open-sans-latin-400-normal.woff"),
    fontsourceFile("open-sans", "open-sans-latin-500-normal.woff"),
    fontsourceFile("open-sans", "open-sans-latin-700-normal.woff")
  );
}

function registerGeist(): boolean {
  return registerFamilyWeights(
    "Geist",
    pkgGeist("dist", "fonts", "geist-sans", "Geist-Regular.ttf"),
    pkgGeist("dist", "fonts", "geist-sans", "Geist-Medium.ttf"),
    pkgGeist("dist", "fonts", "geist-sans", "Geist-Bold.ttf")
  );
}

/** Arial has no bundled 500; map medium to regular so weight 500 resolves predictably. */
function registerArial(): boolean {
  const paths = resolveArial();
  if (!paths) return false;
  const family = "Arial";
  if (registeredFamilies.has(family)) return true;
  if (!fs.existsSync(paths.regular) || !fs.existsSync(paths.bold)) return false;
  try {
    Font.register({
      family,
      fonts: [
        { src: paths.regular, fontWeight: "normal" },
        { src: paths.regular, fontWeight: 500 },
        { src: paths.bold, fontWeight: "bold" },
      ],
    });
    registeredFamilies.add(family);
    return true;
  } catch {
    return false;
  }
}

let activeBodyFamily = "Helvetica";
let activeBoldIsWeight = false;
/** Built-in Helvetica has no embedded 500; line titles use Inter medium when Helvetica is body. */
let lineItemMediumFamily = "";

/**
 * Select and register fonts for one invoice render. Call before `renderToBuffer`.
 */
export function prepareInvoicePdfFonts(preference: InvoicePdfFont): void {
  activeBoldIsWeight = true;
  lineItemMediumFamily = "";

  switch (preference) {
    case "HELVETICA":
      activeBodyFamily = "Helvetica";
      activeBoldIsWeight = false;
      lineItemMediumFamily = registerInter() ? "Inter" : "Helvetica";
      break;
    case "ROBOTO": {
      const ok = registerRoboto();
      activeBodyFamily = ok ? "Roboto" : "Helvetica";
      activeBoldIsWeight = ok;
      lineItemMediumFamily = ok ? "Roboto" : registerInter() ? "Inter" : "Helvetica";
      break;
    }
    case "INTER": {
      const ok = registerInter();
      activeBodyFamily = ok ? "Inter" : "Helvetica";
      activeBoldIsWeight = ok;
      lineItemMediumFamily = ok ? "Inter" : "Helvetica";
      break;
    }
    case "OPEN_SANS": {
      const ok = registerOpenSans();
      activeBodyFamily = ok ? "Open Sans" : "Helvetica";
      activeBoldIsWeight = ok;
      lineItemMediumFamily = ok ? "Open Sans" : registerInter() ? "Inter" : "Helvetica";
      break;
    }
    case "GEIST": {
      const ok = registerGeist();
      activeBodyFamily = ok ? "Geist" : "Helvetica";
      activeBoldIsWeight = ok;
      lineItemMediumFamily = ok ? "Geist" : registerInter() ? "Inter" : "Helvetica";
      break;
    }
    case "ARIAL": {
      const ok = registerArial();
      activeBodyFamily = ok ? "Arial" : "Helvetica";
      activeBoldIsWeight = ok;
      lineItemMediumFamily = ok ? "Arial" : registerInter() ? "Inter" : "Helvetica";
      break;
    }
    default:
      activeBodyFamily = "Helvetica";
      activeBoldIsWeight = false;
      lineItemMediumFamily = registerInter() ? "Inter" : "Helvetica";
  }
}

export function invoicePdfFont(): string {
  return activeBodyFamily;
}

export function invoicePdfBold(): { fontFamily: string; fontWeight?: "bold" } {
  if (activeBoldIsWeight) return { fontFamily: activeBodyFamily, fontWeight: "bold" };
  return { fontFamily: "Helvetica-Bold" };
}

/** Line table: item name, qty, unit price, line total, and summary rows (Subtotal, VAT, amount paid) at medium (500). */
export function invoicePdfLineItemNameStyle(): {
  fontFamily: string;
  fontWeight: 500;
} {
  return {
    fontFamily: lineItemMediumFamily || activeBodyFamily,
    fontWeight: 500,
  };
}
