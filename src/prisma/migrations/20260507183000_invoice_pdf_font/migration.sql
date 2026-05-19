-- CreateEnum
CREATE TYPE "InvoicePdfFont" AS ENUM ('ROBOTO', 'ARIAL', 'HELVETICA', 'GEIST', 'INTER', 'OPEN_SANS');

-- AlterTable
ALTER TABLE "OrgSettings" ADD COLUMN "invoicePdfFont" "InvoicePdfFont" NOT NULL DEFAULT 'HELVETICA';
