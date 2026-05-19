"use client";

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { startTimer, stopTimer } from "@/lib/actions/time-entries";
import { Play, Square, Clock } from "lucide-react";

interface Task {
  id: string;
  title: string;
  projectName: string;
}

interface RunningEntry {
  id: string;
  startedAt: Date;
  taskTitle: string;
  projectName: string;
}

interface Props {
  orgSlug: string;
  runningEntry: RunningEntry | null;
  tasks: Task[];
}

function formatElapsed(startedAt: Date): string {
  const elapsed = Math.floor((Date.now() - new Date(startedAt).getTime()) / 1000);
  const h = Math.floor(elapsed / 3600);
  const m = Math.floor((elapsed % 3600) / 60);
  const s = elapsed % 60;
  return `${h.toString().padStart(2, "0")}:${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`;
}

export function TimeTracker({ orgSlug, runningEntry: initialEntry, tasks }: Props) {
  const [selectedTaskId, setSelectedTaskId] = useState("");
  const [running, setRunning] = useState<RunningEntry | null>(initialEntry);
  const [elapsed, setElapsed] = useState("00:00:00");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!running) return;
    setElapsed(formatElapsed(running.startedAt));
    const interval = setInterval(() => {
      setElapsed(formatElapsed(running.startedAt));
    }, 1000);
    return () => clearInterval(interval);
  }, [running]);

  async function handleStart() {
    if (!selectedTaskId) return;
    setLoading(true);
    await startTimer(orgSlug, selectedTaskId);
    const task = tasks.find((t) => t.id === selectedTaskId)!;
    setRunning({
      id: "new",
      startedAt: new Date(),
      taskTitle: task.title,
      projectName: task.projectName,
    });
    setLoading(false);
  }

  async function handleStop() {
    if (!running) return;
    setLoading(true);
    await stopTimer(orgSlug, running.id);
    setRunning(null);
    setElapsed("00:00:00");
    setLoading(false);
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Clock className="h-4 w-4" />
          Timer
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {running ? (
          <div className="space-y-3">
            <div className="text-center">
              <div className="text-3xl font-mono font-bold text-[var(--primary)]">{elapsed}</div>
              <p className="text-sm text-[var(--muted-foreground)] mt-1">
                {running.taskTitle}
              </p>
              <p className="text-xs text-[var(--muted-foreground)]">{running.projectName}</p>
            </div>
            <Button
              className="w-full"
              variant="destructive"
              onClick={handleStop}
              disabled={loading}
            >
              <Square className="h-4 w-4" />
              Stop timer
            </Button>
          </div>
        ) : (
          <div className="space-y-3">
            <div className="text-center">
              <div className="text-3xl font-mono font-bold text-[var(--muted-foreground)]">
                {elapsed}
              </div>
            </div>
            <Select value={selectedTaskId} onValueChange={setSelectedTaskId}>
              <SelectTrigger>
                <SelectValue placeholder="Select a task…" />
              </SelectTrigger>
              <SelectContent>
                {tasks.map((t) => (
                  <SelectItem key={t.id} value={t.id}>
                    <span className="truncate">
                      {t.projectName} - {t.title}
                    </span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button
              className="w-full"
              onClick={handleStart}
              disabled={loading || !selectedTaskId}
            >
              <Play className="h-4 w-4" />
              Start timer
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
