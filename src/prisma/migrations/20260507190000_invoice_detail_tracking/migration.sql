-- CreateEnum
CREATE TYPE "InvoiceEmailSendKind" AS ENUM ('INITIAL', 'OVERDUE_REMINDER');

-- CreateEnum
CREATE TYPE "InvoiceAttachmentUploader" AS ENUM ('STAFF', 'PUBLIC');

-- AlterTable
ALTER TABLE "OrgSettings" ADD COLUMN "trackInvoiceEmailOpens" BOOLEAN NOT NULL DEFAULT true;

-- CreateTable
CREATE TABLE "InvoiceNote" (
    "id" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "invoiceId" TEXT NOT NULL,
    "authorId" TEXT,
    "content" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "InvoiceNote_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "InvoiceAttachment" (
    "id" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "invoiceId" TEXT NOT NULL,
    "uploadedBy" "InvoiceAttachmentUploader" NOT NULL,
    "uploadedByUserId" TEXT,
    "filename" TEXT NOT NULL,
    "mimeType" TEXT NOT NULL,
    "sizeBytes" INTEGER NOT NULL,
    "storagePath" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "InvoiceAttachment_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "InvoiceEmailSend" (
    "id" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "invoiceId" TEXT NOT NULL,
    "kind" "InvoiceEmailSendKind" NOT NULL,
    "toEmail" TEXT NOT NULL,
    "subject" TEXT,
    "sentAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "InvoiceEmailSend_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "InvoiceEmailOpen" (
    "id" TEXT NOT NULL,
    "sendId" TEXT NOT NULL,
    "openedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "ipAddress" TEXT,
    "userAgent" TEXT,

    CONSTRAINT "InvoiceEmailOpen_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "InvoicePublicView" (
    "id" TEXT NOT NULL,
    "invoiceId" TEXT NOT NULL,
    "viewedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "ipAddress" TEXT,
    "userAgent" TEXT,

    CONSTRAINT "InvoicePublicView_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "InvoiceNote_organizationId_idx" ON "InvoiceNote"("organizationId");

-- CreateIndex
CREATE INDEX "InvoiceNote_invoiceId_idx" ON "InvoiceNote"("invoiceId");

-- CreateIndex
CREATE INDEX "InvoiceAttachment_organizationId_idx" ON "InvoiceAttachment"("organizationId");

-- CreateIndex
CREATE INDEX "InvoiceAttachment_invoiceId_idx" ON "InvoiceAttachment"("invoiceId");

-- CreateIndex
CREATE INDEX "InvoiceEmailSend_organizationId_idx" ON "InvoiceEmailSend"("organizationId");

-- CreateIndex
CREATE INDEX "InvoiceEmailSend_invoiceId_idx" ON "InvoiceEmailSend"("invoiceId");

-- CreateIndex
CREATE INDEX "InvoiceEmailSend_sentAt_idx" ON "InvoiceEmailSend"("sentAt");

-- CreateIndex
CREATE INDEX "InvoiceEmailOpen_sendId_idx" ON "InvoiceEmailOpen"("sendId");

-- CreateIndex
CREATE INDEX "InvoiceEmailOpen_openedAt_idx" ON "InvoiceEmailOpen"("openedAt");

-- CreateIndex
CREATE INDEX "InvoicePublicView_invoiceId_idx" ON "InvoicePublicView"("invoiceId");

-- CreateIndex
CREATE INDEX "InvoicePublicView_invoiceId_viewedAt_idx" ON "InvoicePublicView"("invoiceId", "viewedAt");

-- AddForeignKey
ALTER TABLE "InvoiceNote" ADD CONSTRAINT "InvoiceNote_invoiceId_fkey" FOREIGN KEY ("invoiceId") REFERENCES "Invoice"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "InvoiceAttachment" ADD CONSTRAINT "InvoiceAttachment_invoiceId_fkey" FOREIGN KEY ("invoiceId") REFERENCES "Invoice"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "InvoiceAttachment" ADD CONSTRAINT "InvoiceAttachment_uploadedByUserId_fkey" FOREIGN KEY ("uploadedByUserId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "InvoiceEmailSend" ADD CONSTRAINT "InvoiceEmailSend_invoiceId_fkey" FOREIGN KEY ("invoiceId") REFERENCES "Invoice"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "InvoiceEmailOpen" ADD CONSTRAINT "InvoiceEmailOpen_sendId_fkey" FOREIGN KEY ("sendId") REFERENCES "InvoiceEmailSend"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "InvoicePublicView" ADD CONSTRAINT "InvoicePublicView_invoiceId_fkey" FOREIGN KEY ("invoiceId") REFERENCES "Invoice"("id") ON DELETE CASCADE ON UPDATE CASCADE;
