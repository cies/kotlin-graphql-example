-- AlterTable
ALTER TABLE "TimeEntry" ADD COLUMN "workSortAt" TIMESTAMP(3);

UPDATE "TimeEntry"
SET "workSortAt" = COALESCE("loggedDate"::timestamp, "startedAt", "createdAt")
WHERE "workSortAt" IS NULL;

ALTER TABLE "TimeEntry" ALTER COLUMN "workSortAt" SET NOT NULL;

-- CreateIndex
CREATE INDEX "TimeEntry_organizationId_workSortAt_idx" ON "TimeEntry"("organizationId", "workSortAt");
