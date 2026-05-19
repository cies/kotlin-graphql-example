-- AlterTable
ALTER TABLE "OrgSettings" ADD COLUMN "emailEnvelopeFooterLine" TEXT;

-- CreateTable
CREATE TABLE "InvoiceDeletionVerification" (
    "id" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "invoiceId" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "codeHash" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "InvoiceDeletionVerification_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "InvoiceDeletionVerification_organizationId_idx" ON "InvoiceDeletionVerification"("organizationId");

-- CreateIndex
CREATE INDEX "InvoiceDeletionVerification_invoiceId_userId_idx" ON "InvoiceDeletionVerification"("invoiceId", "userId");

-- AddForeignKey
ALTER TABLE "InvoiceDeletionVerification" ADD CONSTRAINT "InvoiceDeletionVerification_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "Organization"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "InvoiceDeletionVerification" ADD CONSTRAINT "InvoiceDeletionVerification_invoiceId_fkey" FOREIGN KEY ("invoiceId") REFERENCES "Invoice"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "InvoiceDeletionVerification" ADD CONSTRAINT "InvoiceDeletionVerification_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
