"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { addManualTimeEntry } from "@/lib/actions/time-entries";
import { Loader2, PenLine } from "lucide-react";

interface Task {
  id: string;
  title: string;
  projectName: string;
}

interface Props {
  orgSlug: string;
  tasks: Task[];
}

function todayLocalISO(): string {
  const n = new Date();
  const y = n.getFullYear();
  const m = String(n.getMonth() + 1).padStart(2, "0");
  const d = String(n.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

export function ManualTimeEntry({ orgSlug, tasks }: Props) {
  const [taskId, setTaskId] = useState("");
  const [loggedDate, setLoggedDate] = useState(todayLocalISO);
  const [hours, setHours] = useState("");
  const [minutes, setMinutes] = useState("");
  const [description, setDescription] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(false);

    const totalMinutes = (parseInt(hours || "0") * 60) + parseInt(minutes || "0");
    if (totalMinutes <= 0) {
      setError("Please enter a duration greater than 0.");
      return;
    }

    setLoading(true);
    const result = await addManualTimeEntry(orgSlug, {
      taskId,
      manualMinutes: totalMinutes,
      loggedDate,
      description: description || undefined,
    });

    setLoading(false);
    if ("error" in result && result.error) {
      setError(result.error);
    } else {
      setSuccess(true);
      setHours("");
      setMinutes("");
      setLoggedDate(todayLocalISO());
      setDescription("");
      setTimeout(() => setSuccess(false), 3000);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <PenLine className="h-4 w-4" />
          Manual Entry
        </CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit} className="space-y-3">
          {error && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
          {success && (
            <Alert>
              <AlertDescription>Time entry added.</AlertDescription>
            </Alert>
          )}

          <div className="space-y-1.5">
            <Label>Task *</Label>
            <Select value={taskId} onValueChange={setTaskId} required>
              <SelectTrigger>
                <SelectValue placeholder="Select task…" />
              </SelectTrigger>
              <SelectContent>
                {tasks.map((t) => (
                  <SelectItem key={t.id} value={t.id}>
                    {t.projectName} - {t.title}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="manual-time-date">Date *</Label>
            <Input
              id="manual-time-date"
              type="date"
              required
              value={loggedDate}
              max={todayLocalISO()}
              onChange={(e) => setLoggedDate(e.target.value)}
            />
            <p className="text-xs text-[var(--muted-foreground)]">
              Calendar day this time applies to.
            </p>
          </div>

          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1.5">
              <Label>Hours</Label>
              <Input
                type="number"
                min="0"
                max="999"
                value={hours}
                onChange={(e) => setHours(e.target.value)}
                placeholder="0"
              />
            </div>
            <div className="space-y-1.5">
              <Label>Minutes</Label>
              <Input
                type="number"
                min="0"
                max="59"
                value={minutes}
                onChange={(e) => setMinutes(e.target.value)}
                placeholder="30"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label>Description</Label>
            <Input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Optional description…"
            />
          </div>

          <Button type="submit" className="w-full" disabled={loading || !taskId}>
            {loading && <Loader2 className="h-4 w-4 animate-spin" />}
            Add entry
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
