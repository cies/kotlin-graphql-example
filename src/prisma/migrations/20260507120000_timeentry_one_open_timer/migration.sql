-- At most one running timer (started, not ended, not manual-only) per org + user.
-- If this fails, clean duplicate open rows: same orgId+userId with endedAt IS NULL, startedAt IS NOT NULL, manualMinutes IS NULL.
CREATE UNIQUE INDEX "TimeEntry_one_open_timer_per_org_user"
ON "TimeEntry" ("organizationId", "userId")
WHERE "endedAt" IS NULL
  AND "startedAt" IS NOT NULL
  AND "manualMinutes" IS NULL;
