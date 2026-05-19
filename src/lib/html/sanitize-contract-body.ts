import DOMPurify from "isomorphic-dompurify";

/** Same rules as contract save - use for any HTML rendered from stored contract bodies. */
export function sanitizeContractBodyHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    ALLOWED_TAGS: [
      "p", "br", "strong", "b", "em", "i", "u", "s", "del",
      "h1", "h2", "h3", "h4", "h5", "h6",
      "ul", "ol", "li",
      "blockquote", "pre", "code",
      "a", "hr",
      "div", "span",
    ],
    ALLOWED_ATTR: ["href", "target", "rel", "class"],
  });
}
