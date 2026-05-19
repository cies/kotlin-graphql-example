-- AlterTable
ALTER TABLE "OrgSettings" ADD COLUMN "invoiceNumberRandomLength" INTEGER NOT NULL DEFAULT 6;
ALTER TABLE "OrgSettings" ADD COLUMN "receiptNumberRandomLength" INTEGER NOT NULL DEFAULT 6;
