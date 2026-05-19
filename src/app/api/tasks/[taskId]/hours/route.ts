import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/lib/auth/auth";
import { prisma } from "@/lib/db/prisma";
import { getTaskHourSummary } from "@/lib/actions/time-entries";

export async function GET(
  _req: NextRequest,
  { params }: { params: Promise<{ taskId: string }> }
) {
  const { taskId } = await params;
  const session = await auth();
  if (!session?.user) return NextResponse.json({ error: "Unauthorized" }, { status: 401 });

  const task = await prisma.task.findUnique({
    where: { id: taskId },
    select: { organizationId: true },
  });

  if (!task) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const summary = await getTaskHourSummary(task.organizationId, taskId);
  return NextResponse.json(summary);
}
