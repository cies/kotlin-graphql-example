"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  duplicateProject,
  deleteProject,
  setProjectStatus,
  togglePinProject,
} from "@/lib/actions/projects";
import type { ProjectStatusValue } from "@/lib/projects/project-status";
import { ChevronDown, FileText, Plus } from "lucide-react";
import { toast } from "sonner";

interface Props {
  orgSlug: string;
  projectId: string;
  isPinned: boolean;
  exportHref: string;
  editHref: string;
  newTaskHref: string;
  invoiceHref: string;
  customerPreviewHref: string;
}

export function ProjectDetailToolbar({
  orgSlug,
  projectId,
  isPinned,
  exportHref,
  editHref,
  newTaskHref,
  invoiceHref,
  customerPreviewHref,
}: Props) {
  const router = useRouter();
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [pinned, setPinned] = useState(isPinned);

  useEffect(() => {
    setPinned(isPinned);
  }, [isPinned]);

  async function runStatus(status: ProjectStatusValue) {
    const res = await setProjectStatus(orgSlug, projectId, status);
    if ("error" in res) {
      toast.error(res.error);
      return;
    }
    toast.success("Status updated");
    router.refresh();
  }

  async function runPin() {
    const res = await togglePinProject(orgSlug, projectId);
    if ("error" in res) {
      toast.error(res.error);
      return;
    }
    setPinned(res.pinned);
    toast.success(res.pinned ? "Pinned" : "Unpinned");
    router.refresh();
  }

  async function runCopy() {
    const res = await duplicateProject(orgSlug, projectId);
    if ("error" in res || !res.projectId) {
      toast.error("error" in res ? res.error : "Could not copy project");
      return;
    }
    toast.success("Project copied");
    router.push(`/${orgSlug}/projects/${res.projectId}`);
  }

  async function runDelete() {
    const res = await deleteProject(orgSlug, projectId);
    if ("error" in res) {
      toast.error(res.error);
      return;
    }
    toast.success("Project deleted");
    router.push(`/${orgSlug}/projects`);
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-2 justify-end">
        <Button asChild size="sm" className="gap-1">
          <Link href={newTaskHref}>
            <Plus className="h-4 w-4" />
            New Task
          </Link>
        </Button>
        <Button variant="outline" size="sm" className="gap-1" asChild>
          <Link href={invoiceHref}>
            <FileText className="h-4 w-4" />
            Invoice project
          </Link>
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="sm" className="gap-1">
              More
              <ChevronDown className="h-4 w-4 opacity-70" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuItem onClick={() => void runPin()}>
              {pinned ? "Unpin project" : "Pin project"}
            </DropdownMenuItem>
            <DropdownMenuItem asChild>
              <Link href={editHref}>Edit project</Link>
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => void runCopy()}>Copy project</DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => void runStatus("NOT_STARTED")}>
              Mark as not started
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => void runStatus("ACTIVE")}>
              Mark as in progress
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => void runStatus("PAUSED")}>
              Mark as on hold
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => void runStatus("ARCHIVED")}>
              Mark as cancelled
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem asChild>
              <a href={exportHref}>Export project data</a>
            </DropdownMenuItem>
            <DropdownMenuItem asChild>
              <Link href={customerPreviewHref}>View project as customer</Link>
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem
              className="text-red-600 focus:text-red-600"
              onClick={() => setDeleteOpen(true)}
            >
              Delete project
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <ConfirmDialog
        open={deleteOpen}
        title="Delete project?"
        description="This permanently deletes the project and its tasks. Time entries linked to tasks may be restricted depending on your data rules."
        confirmLabel="Delete"
        onCancel={() => setDeleteOpen(false)}
        onConfirm={() => {
          setDeleteOpen(false);
          void runDelete();
        }}
      />
    </>
  );
}
