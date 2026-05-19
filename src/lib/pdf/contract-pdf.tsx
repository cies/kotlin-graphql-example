import React from "react";
import {
  Document,
  Page,
  Text,
  View,
  StyleSheet,
  Svg,
  Path,
  renderToBuffer,
} from "@react-pdf/renderer";
import type { ContractStatus } from "@prisma/client";
import { parse, type HTMLElement as ParsedHtmlElement, type Node } from "node-html-parser";

const styles = StyleSheet.create({
  page: { padding: 40, fontFamily: "Helvetica", fontSize: 10, color: "#1a1a1a" },
  header: { marginBottom: 20 },
  title: { fontSize: 18, fontFamily: "Helvetica-Bold", marginBottom: 4 },
  subtitle: { color: "#4b5563" },
  sectionTitle: { marginTop: 16, marginBottom: 6, fontFamily: "Helvetica-Bold", fontSize: 11 },
  body: { lineHeight: 1.4, color: "#111827" },
  auditPanel: { marginTop: 20, borderTopWidth: 1, borderTopColor: "#e5e7eb", paddingTop: 10 },
  auditRow: { marginBottom: 4 },
  signatureBox: { marginTop: 8, borderWidth: 1, borderColor: "#d1d5db", padding: 8, minHeight: 48 },
  h1: { fontSize: 16, fontFamily: "Helvetica-Bold", marginTop: 12, marginBottom: 4 },
  h2: { fontSize: 14, fontFamily: "Helvetica-Bold", marginTop: 10, marginBottom: 4 },
  h3: { fontSize: 12, fontFamily: "Helvetica-Bold", marginTop: 8, marginBottom: 4 },
  h4: { fontSize: 11, fontFamily: "Helvetica-Bold", marginTop: 6, marginBottom: 2 },
  paragraph: { marginBottom: 6, lineHeight: 1.5 },
  listItem: { marginBottom: 4, paddingLeft: 12, lineHeight: 1.5 },
  blockquote: {
    marginLeft: 12,
    paddingLeft: 8,
    borderLeftWidth: 2,
    borderLeftColor: "#d1d5db",
    color: "#6b7280",
    marginBottom: 6,
  },
  codeBlock: {
    fontFamily: "Courier",
    fontSize: 9,
    backgroundColor: "#f3f4f6",
    padding: 6,
    marginBottom: 6,
  },
  inlineCode: { fontFamily: "Courier", fontSize: 9 },
  hr: { borderBottomWidth: 1, borderBottomColor: "#e5e7eb", marginTop: 8, marginBottom: 8 },
});

export interface ContractPdfData {
  contract: {
    title: string;
    bodyHtml: string;
    status: ContractStatus;
    updatedAt: Date;
  };
  organization: {
    name: string;
  };
  customer: {
    name: string;
    email?: string;
  };
  signature?: {
    signerName: string;
    signerEmail: string;
    signatureSvg: string;
    ipAddress?: string;
    userAgent?: string;
    signedAt: Date;
  };
}

function decodeEntities(text: string): string {
  return text
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
}

function renderInlineChildren(node: ParsedHtmlElement): React.ReactNode[] {
  const children: React.ReactNode[] = [];
  let i = 0;
  for (const child of node.childNodes) {
    const key = i++;
    if (child.nodeType === 3) {
      const t = decodeEntities(child.text);
      if (t) children.push(<Text key={key}>{t}</Text>);
    } else {
      const el = child as ParsedHtmlElement;
      const tag = el.tagName?.toLowerCase();
      const inner = renderInlineChildren(el);
      if (tag === "strong" || tag === "b") {
        children.push(
          <Text key={key} style={{ fontFamily: "Helvetica-Bold" }}>
            {inner}
          </Text>
        );
      } else if (tag === "em" || tag === "i") {
        children.push(
          <Text key={key} style={{ fontFamily: "Helvetica-Oblique" }}>
            {inner}
          </Text>
        );
      } else if (tag === "u") {
        children.push(
          <Text key={key} style={{ textDecoration: "underline" }}>
            {inner}
          </Text>
        );
      } else if (tag === "s" || tag === "del") {
        children.push(
          <Text key={key} style={{ textDecoration: "line-through" }}>
            {inner}
          </Text>
        );
      } else if (tag === "code") {
        children.push(
          <Text key={key} style={styles.inlineCode}>
            {inner}
          </Text>
        );
      } else if (tag === "a") {
        children.push(
          <Text key={key} style={{ color: "#3b82f6", textDecoration: "underline" }}>
            {inner}
          </Text>
        );
      } else if (tag === "br") {
        children.push(<Text key={key}>{"\n"}</Text>);
      } else {
        children.push(<Text key={key}>{inner}</Text>);
      }
    }
  }
  return children;
}

function renderNode(node: Node, idx: number): React.ReactNode {
  if (node.nodeType === 3) {
    const t = decodeEntities(node.text.trim());
    return t ? <Text key={idx} style={styles.body}>{t}</Text> : null;
  }

  const el = node as ParsedHtmlElement;
  const tag = el.tagName?.toLowerCase();

  if (!tag) return null;

  if (tag === "h1") {
    return <Text key={idx} style={styles.h1}>{renderInlineChildren(el)}</Text>;
  }
  if (tag === "h2") {
    return <Text key={idx} style={styles.h2}>{renderInlineChildren(el)}</Text>;
  }
  if (tag === "h3") {
    return <Text key={idx} style={styles.h3}>{renderInlineChildren(el)}</Text>;
  }
  if (tag === "h4" || tag === "h5" || tag === "h6") {
    return <Text key={idx} style={styles.h4}>{renderInlineChildren(el)}</Text>;
  }
  if (tag === "p") {
    const inner = renderInlineChildren(el);
    if (!inner.length) return <Text key={idx} style={{ marginBottom: 4 }}>{" "}</Text>;
    return <Text key={idx} style={styles.paragraph}>{inner}</Text>;
  }
  if (tag === "br") {
    return <Text key={idx}>{"\n"}</Text>;
  }
  if (tag === "hr") {
    return <View key={idx} style={styles.hr} />;
  }
  if (tag === "blockquote") {
    return (
      <View key={idx} style={styles.blockquote}>
        {el.childNodes.map((c, i) => renderNode(c, i))}
      </View>
    );
  }
  if (tag === "pre" || tag === "code") {
    const text = decodeEntities(el.text);
    return <Text key={idx} style={styles.codeBlock}>{text}</Text>;
  }
  if (tag === "ul" || tag === "ol") {
    const items: React.ReactNode[] = [];
    let counter = 0;
    for (const li of el.querySelectorAll("li")) {
      counter++;
      const bullet = tag === "ul" ? "•" : `${counter}.`;
      items.push(
        <Text key={counter} style={styles.listItem}>
          {bullet}{"  "}{renderInlineChildren(li)}
        </Text>
      );
    }
    return <View key={idx}>{items}</View>;
  }
  if (tag === "div" || tag === "section" || tag === "article") {
    return (
      <View key={idx}>
        {el.childNodes.map((c, i) => renderNode(c, i))}
      </View>
    );
  }
  if (tag === "strong" || tag === "b" || tag === "em" || tag === "i" || tag === "u" || tag === "s") {
    return <Text key={idx} style={styles.body}>{renderInlineChildren(el)}</Text>;
  }
  if (tag === "span") {
    return <Text key={idx} style={styles.body}>{renderInlineChildren(el)}</Text>;
  }

  return (
    <View key={idx}>
      {el.childNodes.map((c, i) => renderNode(c, i))}
    </View>
  );
}

function renderHtmlToPdf(html: string): React.ReactNode[] {
  const root = parse(html);
  const nodes: React.ReactNode[] = [];
  let idx = 0;
  for (const child of root.childNodes) {
    const rendered = renderNode(child, idx++);
    if (rendered) nodes.push(rendered);
  }
  return nodes;
}

function extractSvgPath(signatureSvg: string): string | null {
  const match = signatureSvg.match(/d="([^"]+)"/);
  return match?.[1] ?? null;
}

export function ContractPdfDocument({ data }: { data: ContractPdfData }) {
  const signaturePath = data.signature ? extractSvgPath(data.signature.signatureSvg) : null;

  return (
    <Document>
      <Page size="A4" style={styles.page}>
        <View style={styles.header}>
          <Text style={styles.title}>{data.contract.title}</Text>
          <Text style={styles.subtitle}>
            {data.organization.name} · Status: {data.contract.status}
          </Text>
        </View>

        <Text style={styles.sectionTitle}>Parties</Text>
        <Text style={styles.body}>
          Provider: {data.organization.name}{"\n"}
          Customer: {data.customer.name}
          {data.customer.email ? ` (${data.customer.email})` : ""}
        </Text>

        <Text style={styles.sectionTitle}>Contract body</Text>
        <View>{renderHtmlToPdf(data.contract.bodyHtml)}</View>

        <View style={styles.auditPanel}>
          <Text style={styles.sectionTitle}>Audit panel</Text>
          {data.signature ? (
            <>
              <Text style={styles.auditRow}>Signer: {data.signature.signerName}</Text>
              <Text style={styles.auditRow}>Email: {data.signature.signerEmail}</Text>
              <Text style={styles.auditRow}>
                Signed at: {new Date(data.signature.signedAt).toLocaleString("en-GB")}
              </Text>
              <Text style={styles.auditRow}>IP address: {data.signature.ipAddress || "-"}</Text>
              <Text style={styles.auditRow}>
                User agent: {data.signature.userAgent || "-"}
              </Text>
              <View style={styles.signatureBox}>
                {signaturePath ? (
                  <Svg viewBox="0 0 500 120" width={200} height={48}>
                    <Path d={signaturePath} stroke="#111827" fill="none" strokeWidth={2} />
                  </Svg>
                ) : (
                  <Text>Signature captured</Text>
                )}
              </View>
            </>
          ) : (
            <Text style={styles.auditRow}>Not signed yet.</Text>
          )}
        </View>
      </Page>
    </Document>
  );
}

export async function generateContractPdf(data: ContractPdfData): Promise<Buffer> {
  const buffer = await renderToBuffer(<ContractPdfDocument data={data} />);
  return Buffer.from(buffer);
}
