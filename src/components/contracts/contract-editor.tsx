"use client";

import { useEditor, EditorContent } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import Link from "@tiptap/extension-link";
import Placeholder from "@tiptap/extension-placeholder";
import Underline from "@tiptap/extension-underline";
import DOMPurify from "isomorphic-dompurify";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  Bold, Italic, Underline as UnderlineIcon, Heading1, Heading2, Heading3,
  List, ListOrdered, Link as LinkIcon, Quote, Minus, Undo, Redo,
  Eye, Code, ChevronDown,
} from "lucide-react";

const MERGE_TAGS = [
  "{{customer.companyName}}",
  "{{customer.firstName}}",
  "{{customer.lastName}}",
  "{{customer.email}}",
  "{{org.name}}",
  "{{contract.title}}",
];

interface Props {
  value: string;
  onChange: (html: string) => void;
}

function ToolbarButton({
  onClick,
  active,
  disabled,
  title,
  children,
}: {
  onClick: () => void;
  active?: boolean;
  disabled?: boolean;
  title?: string;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      title={title}
      disabled={disabled}
      onClick={onClick}
      className={`h-8 w-8 inline-flex items-center justify-center rounded text-sm transition-colors
        ${active ? "bg-[var(--primary)] text-white" : "hover:bg-[var(--muted)] text-[var(--foreground)]"}
        ${disabled ? "opacity-40 cursor-not-allowed" : "cursor-pointer"}`}
    >
      {children}
    </button>
  );
}

export function ContractEditor({ value, onChange }: Props) {
  const [showPreview, setShowPreview] = useState(false);
  const [showTagMenu, setShowTagMenu] = useState(false);
  const [linkDialogOpen, setLinkDialogOpen] = useState(false);
  const [linkUrl, setLinkUrl] = useState("");

  const editor = useEditor({
    immediatelyRender: false,
    extensions: [
      StarterKit,
      Underline,
      Link.configure({ openOnClick: false }),
      Placeholder.configure({ placeholder: "Write your contract here…" }),
    ],
    content: value,
    onUpdate: ({ editor }) => {
      onChange(editor.getHTML());
    },
  });

  function insertTag(tag: string) {
    editor?.chain().focus().insertContent(tag).run();
    setShowTagMenu(false);
  }

  function openLinkDialog() {
    const existing = editor?.getAttributes("link").href ?? "";
    setLinkUrl(existing);
    setLinkDialogOpen(true);
  }

  function applyLink() {
    if (!editor) return;
    if (linkUrl.trim()) {
      editor.chain().focus().setLink({ href: linkUrl.trim() }).run();
    } else {
      editor.chain().focus().unsetLink().run();
    }
    setLinkDialogOpen(false);
    setLinkUrl("");
  }

  if (!editor) return null;

  const previewHtml = showPreview
    ? DOMPurify.sanitize(editor.getHTML(), { USE_PROFILES: { html: true } })
    : "";

  return (
    <div className="rounded-lg border border-[var(--border)] overflow-hidden">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-0.5 border-b border-[var(--border)] bg-[var(--muted)]/30 px-2 py-1.5">
        <ToolbarButton
          onClick={() => editor.chain().focus().toggleBold().run()}
          active={editor.isActive("bold")}
          title="Bold"
        ><Bold className="h-3.5 w-3.5" /></ToolbarButton>
        <ToolbarButton
          onClick={() => editor.chain().focus().toggleItalic().run()}
          active={editor.isActive("italic")}
          title="Italic"
        ><Italic className="h-3.5 w-3.5" /></ToolbarButton>
        <ToolbarButton
          onClick={() => editor.chain().focus().toggleUnderline().run()}
          active={editor.isActive("underline")}
          title="Underline"
        ><UnderlineIcon className="h-3.5 w-3.5" /></ToolbarButton>

        <div className="w-px h-5 bg-[var(--border)] mx-1" />

        <ToolbarButton
          onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
          active={editor.isActive("heading", { level: 1 })}
          title="Heading 1"
        ><Heading1 className="h-3.5 w-3.5" /></ToolbarButton>
        <ToolbarButton
          onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
          active={editor.isActive("heading", { level: 2 })}
          title="Heading 2"
        ><Heading2 className="h-3.5 w-3.5" /></ToolbarButton>
        <ToolbarButton
          onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()}
          active={editor.isActive("heading", { level: 3 })}
          title="Heading 3"
        ><Heading3 className="h-3.5 w-3.5" /></ToolbarButton>

        <div className="w-px h-5 bg-[var(--border)] mx-1" />

        <ToolbarButton
          onClick={() => editor.chain().focus().toggleBulletList().run()}
          active={editor.isActive("bulletList")}
          title="Bullet list"
        ><List className="h-3.5 w-3.5" /></ToolbarButton>
        <ToolbarButton
          onClick={() => editor.chain().focus().toggleOrderedList().run()}
          active={editor.isActive("orderedList")}
          title="Numbered list"
        ><ListOrdered className="h-3.5 w-3.5" /></ToolbarButton>
        <ToolbarButton
          onClick={() => editor.chain().focus().toggleBlockquote().run()}
          active={editor.isActive("blockquote")}
          title="Blockquote"
        ><Quote className="h-3.5 w-3.5" /></ToolbarButton>
        <ToolbarButton
          onClick={() => editor.chain().focus().toggleCode().run()}
          active={editor.isActive("code")}
          title="Inline code"
        ><Code className="h-3.5 w-3.5" /></ToolbarButton>
        <ToolbarButton
          onClick={() => editor.chain().focus().setHorizontalRule().run()}
          title="Horizontal rule"
        ><Minus className="h-3.5 w-3.5" /></ToolbarButton>
        <ToolbarButton onClick={openLinkDialog} active={editor.isActive("link")} title="Link">
          <LinkIcon className="h-3.5 w-3.5" />
        </ToolbarButton>

        <div className="w-px h-5 bg-[var(--border)] mx-1" />

        <ToolbarButton
          onClick={() => editor.chain().focus().undo().run()}
          disabled={!editor.can().undo()}
          title="Undo"
        ><Undo className="h-3.5 w-3.5" /></ToolbarButton>
        <ToolbarButton
          onClick={() => editor.chain().focus().redo().run()}
          disabled={!editor.can().redo()}
          title="Redo"
        ><Redo className="h-3.5 w-3.5" /></ToolbarButton>

        <div className="flex-1" />

        {/* Merge tags dropdown */}
        <div className="relative">
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="h-7 text-xs"
            onClick={() => setShowTagMenu((v) => !v)}
          >
            Insert tag <ChevronDown className="h-3 w-3 ml-1" />
          </Button>
          {showTagMenu && (
            <div className="absolute right-0 top-8 z-50 bg-white border border-[var(--border)] rounded-md shadow-lg min-w-[200px]">
              {MERGE_TAGS.map((tag) => (
                <button
                  key={tag}
                  type="button"
                  className="block w-full text-left px-3 py-1.5 text-xs font-mono hover:bg-[var(--muted)] transition-colors"
                  onClick={() => insertTag(tag)}
                >
                  {tag}
                </button>
              ))}
            </div>
          )}
        </div>

        <Button
          type="button"
          variant={showPreview ? "default" : "outline"}
          size="sm"
          className="h-7 text-xs ml-1"
          onClick={() => setShowPreview((v) => !v)}
        >
          <Eye className="h-3 w-3 mr-1" />
          Preview
        </Button>
      </div>

      {/* Editor + preview split */}
      <div className={`flex ${showPreview ? "divide-x divide-[var(--border)]" : ""}`}>
        <div className={showPreview ? "w-1/2" : "w-full"}>
          <EditorContent
            editor={editor}
            className="min-h-[400px] max-h-[600px] overflow-y-auto p-4 prose prose-sm max-w-none focus:outline-none [&_.ProseMirror]:outline-none [&_.ProseMirror]:min-h-[360px]"
          />
        </div>
        {showPreview && (
          <div className="w-1/2 p-4 min-h-[400px] max-h-[600px] overflow-y-auto bg-white">
            <div
              className="prose prose-sm max-w-none"
              dangerouslySetInnerHTML={{ __html: previewHtml }}
            />
          </div>
        )}
      </div>

      <Dialog open={linkDialogOpen} onOpenChange={(o) => { if (!o) { setLinkDialogOpen(false); setLinkUrl(""); } }}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>Insert link</DialogTitle>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="link-url">URL</Label>
            <Input
              id="link-url"
              type="url"
              placeholder="https://example.com"
              value={linkUrl}
              onChange={(e) => setLinkUrl(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && applyLink()}
              autoFocus
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => { setLinkDialogOpen(false); setLinkUrl(""); }}>
              Cancel
            </Button>
            <Button onClick={applyLink}>
              {linkUrl.trim() ? "Apply" : "Remove link"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
