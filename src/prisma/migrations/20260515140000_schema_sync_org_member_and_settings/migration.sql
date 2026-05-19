-- Columns present in prisma/schema.prisma but missing from earlier migration SQL.
-- Without these, Prisma P2022 breaks app + workers (e.g. reminder job + org signup).

ALTER TABLE "OrgSettings" ADD COLUMN IF NOT EXISTS "numberFormatStyle" TEXT NOT NULL DEFAULT 'COMMA_DOT';

ALTER TABLE "OrganizationMember" ADD COLUMN IF NOT EXISTS "hourlyRate" DECIMAL(10,2);
ALTER TABLE "OrganizationMember" ADD COLUMN IF NOT EXISTS "isRetainer" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "OrganizationMember" ADD COLUMN IF NOT EXISTS "retainerHours" DECIMAL(8,2);
