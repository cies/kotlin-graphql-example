import type { BrandCtx } from "./index";

export function wrap(inner: string, brand: BrandCtx): string {
  const accent = brand.accentColor || "#1e3a5f";
  const logoBlock = brand.hasLogo
    ? `<img src="cid:logo@crm" alt="${esc(brand.companyName)}" style="max-width:180px;max-height:70px;display:block" />`
    : `<span style="font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.5px">${esc(brand.companyName)}</span>`;

  return `<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Email</title></head>
<body style="margin:0;padding:0;background:#e9eef4;font-family:Georgia,'Times New Roman',serif">
  <table width="100%" cellpadding="0" cellspacing="0" role="presentation">
    <tr><td align="center" style="padding:40px 16px">
      <table width="600" cellpadding="0" cellspacing="0" role="presentation" style="max-width:600px;width:100%">
        <!-- Header band -->
        <tr><td style="background:${accent};padding:28px 40px;border-radius:4px 4px 0 0" align="center">
          ${logoBlock}
        </td></tr>
        <!-- Ruled divider -->
        <tr><td style="background:#ffffff;border-left:1px solid #d1d5db;border-right:1px solid #d1d5db;padding:0 40px">
          <hr style="border:none;border-top:3px solid ${accent};margin:0" />
        </td></tr>
        <!-- Body -->
        <tr><td style="background:#ffffff;border-left:1px solid #d1d5db;border-right:1px solid #d1d5db;padding:32px 40px;color:#1a1a1a;font-size:15px;line-height:1.7">
          ${inner}
        </td></tr>
        <!-- Bottom rule -->
        <tr><td style="background:#ffffff;border-left:1px solid #d1d5db;border-right:1px solid #d1d5db;padding:0 40px">
          <hr style="border:none;border-top:1px solid #d1d5db;margin:0" />
        </td></tr>
        <!-- Footer -->
        <tr><td align="center" style="background:${accent};border-radius:0 0 4px 4px;padding:16px 40px;color:rgba(255,255,255,0.75);font-family:-apple-system,Arial,sans-serif;font-size:12px;line-height:1.5;text-align:center">
          ${esc(brand.footerLegalLine)}
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
