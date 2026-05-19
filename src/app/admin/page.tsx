import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { auth } from "@/lib/auth/auth";
import { isPlatformAdminEmail } from "@/lib/platform-admin";
import {
  listOrganizationsForPlatformAdmin,
  listQuotationRequestsForPlatformAdmin,
} from "@/lib/actions/platform-admin";
import { ComplimentaryPremiumTable } from "@/components/platform-admin/complimentary-premium-table";
import { QuotationRequestsTable } from "@/components/platform-admin/quotation-requests-table";

export default async function PlatformAdminPage() {
  const session = await auth();
  if (!session?.user) {
    redirect("/auth/login?callbackUrl=/admin");
  }
  if (session.user.userType !== "STAFF") {
    notFound();
  }
  if (!isPlatformAdminEmail(session.user.email)) {
    notFound();
  }

  const result = await listOrganizationsForPlatformAdmin();
  if ("error" in result) {
    notFound();
  }

  const quotesResult = await listQuotationRequestsForPlatformAdmin();
  if ("error" in quotesResult) {
    notFound();
  }
  const quotationRows = quotesResult.requests;

  const backSlug = session.user.organizationSlug ?? "";

  return (
    <div className="max-w-5xl mx-auto px-4 py-10 space-y-14">
      <div className="mb-8 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">Platform admin</h1>
          <p className="text-[var(--muted-foreground)] mt-1">
            Complimentary Premium and quotation requests
          </p>
        </div>
        {backSlug && (
          <Link
            href={`/${backSlug}/settings`}
            className="text-sm text-[var(--primary)] underline underline-offset-2"
          >
            Back to CRM
          </Link>
        )}
      </div>

      <section>
        <h2 className="text-lg font-semibold mb-4">Quotation requests</h2>
        <p className="text-sm text-[var(--muted-foreground)] mb-6">
          Messages sent from the public contact form on the home page.
        </p>
        <QuotationRequestsTable rows={quotationRows} />
      </section>

      <section>
        <h2 className="text-lg font-semibold mb-4">Complimentary Premium</h2>
        <p className="text-sm text-[var(--muted-foreground)] mb-6">
          Grant Premium to any organization without Stripe.
        </p>
        <ComplimentaryPremiumTable initialRows={result.organizations} />
      </section>
    </div>
  );
}
