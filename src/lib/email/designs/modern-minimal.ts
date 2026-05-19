import type { BrandCtx } from "./index";

export function wrap(inner: string, brand: BrandCtx): string {
  const accent = brand.accentColor || "#2563eb";
  const logoBlock = brand.hasLogo
    ? `<img src="cid:logo@crm" alt="${esc(brand.companyName)}" style="max-width:160px;max-height:60px;display:block;margin-bottom:16px" />`
    : `<span style="font-size:20px;font-weight:700;color:${accent}">${esc(brand.companyName)}</span>`;

  return `<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Email</title></head>
<body style="margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif">
  <table width="100%" cellpadding="0" cellspacing="0" role="presentation">
    <tr><td align="center" style="padding:40px 16px">
      <table width="600" cellpadding="0" cellspacing="0" role="presentation" style="max-width:600px;width:100%">
        <!-- Header -->
        <tr><td style="background:#ffffff;border-top:4px solid ${accent};border-radius:8px 8px 0 0;padding:28px 40px 20px">
          ${logoBlock}
        </td></tr>
        <!-- Body -->
        <tr><td style="background:#ffffff;padding:0 40px 28px;color:#0f172a;font-size:15px;line-height:1.65">
          ${inner}
        </td></tr>
        <!-- Footer -->
        <tr><td align="center" style="background:#f8fafc;border-top:1px solid #e2e8f0;border-radius:0 0 8px 8px;padding:16px 40px;color:#64748b;font-size:12px;line-height:1.5;text-align:center">
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
