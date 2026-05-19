import DOMPurify from "isomorphic-dompurify";

/**
 * Sanitizes composed email HTML before `dangerouslySetInnerHTML`.
 * Allows typical marketing-email structure (tables, inline styles) while stripping scripts.
 */
export function sanitizeEmailPreviewHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    WHOLE_DOCUMENT: true,
    USE_PROFILES: { html: true },
    ADD_TAGS: ["style", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "img"],
    ADD_ATTR: [
      "style",
      "bgcolor",
      "align",
      "valign",
      "width",
      "height",
      "cellpadding",
      "cellspacing",
      "border",
      "colspan",
      "rowspan",
      "src",
      "alt",
      "role",
    ],
  });
}
