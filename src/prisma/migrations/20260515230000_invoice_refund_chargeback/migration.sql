-- AlterEnum
ALTER TYPE "InvoiceStatus" ADD VALUE 'REFUNDED';
ALTER TYPE "InvoiceStatus" ADD VALUE 'CHARGEBACK';

-- AlterTable
ALTER TABLE "Payment" ADD COLUMN "refundedAmount" DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE "Payment" ADD COLUMN "stripeDisputeId" TEXT;
ALTER TABLE "Payment" ADD COLUMN "disputeStatus" TEXT;
