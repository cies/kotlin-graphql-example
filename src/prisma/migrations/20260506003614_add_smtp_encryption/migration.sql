-- CreateEnum
CREATE TYPE "SmtpEncryption" AS ENUM ('TLS', 'SSL', 'NONE');

-- AlterTable
ALTER TABLE "OrgSettings" ADD COLUMN     "smtpEncryption" "SmtpEncryption" NOT NULL DEFAULT 'TLS';
