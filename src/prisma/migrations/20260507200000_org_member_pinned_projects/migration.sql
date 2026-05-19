-- AlterTable
ALTER TABLE "OrganizationMember" ADD COLUMN IF NOT EXISTS "pinnedProjectIds" TEXT[] DEFAULT ARRAY[]::TEXT[];
