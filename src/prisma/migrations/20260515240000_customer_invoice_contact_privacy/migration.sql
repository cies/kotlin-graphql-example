-- AlterTable
ALTER TABLE "Customer" ADD COLUMN "hideEmailOnInvoice" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "Customer" ADD COLUMN "hidePhoneOnInvoice" BOOLEAN NOT NULL DEFAULT false;
