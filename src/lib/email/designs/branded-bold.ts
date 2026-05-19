import type { BrandCtx } from "./index";

export function wrap(inner: string, brand: BrandCtx): string {
  const accent = brand.accentColor || "#7c3aed";
  const logoBlock = brand.hasLogo
    ? `<img src="cid:logo@crm" alt="${esc(brand.companyName)}" style="max-width:160px;max-height:56px;display:block" />`
    : `<span style="font-size:24px;font-weight:900;color:#ffffff;text-transform:uppercase;letter-spacing:1px">${esc(brand.companyName)}</span>`;

  return `<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Email</title></head>
<body style="margin:0;padding:0;background:#f4f4f8;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif">
  <table width="100%" cellpadding="0" cellspacing="0" role="presentation">
    <tr><td align="center" style="padding:0 0 40px">
      <table width="600" cellpadding="0" cellspacing="0" role="presentation" style="max-width:600px;width:100%">
        <!-- Bold header band -->
        <tr><td style="background:${accent};padding:36px 40px 32px;position:relative">
          ${logoBlock}
          <div style="position:absolute;right:0;bottom:0;width:80px;height:80px;border-radius:50% 0 0 0;background:rgba(255,255,255,0.08)"></div>
        </td></tr>
        <!-- White body -->
        <tr><td style="background:#ffffff;padding:36px 40px;color:#111827;font-size:15px;line-height:1.65;box-shadow:0 2px 8px rgba(0,0,0,0.06)">
          ${inner}
        </td></tr>
        <!-- Accent stripe footer -->
        <tr><td align="center" style="background:${accent}18;border-top:3px solid ${accent};padding:16px 40px;color:#4b5563;font-size:12px;line-height:1.5;text-align:center">
          <strong style="color:${accent}">${esc(brand.footerLegalLine)}</strong>
          ${brand.footerHtml ? `<br/>${brand.footerHtml}` : ""}
        </td></tr>
      </table>
    </td></tr>
  </table>
</body>
</html>`;
}

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
