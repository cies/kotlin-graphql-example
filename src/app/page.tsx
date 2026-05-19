import Link from "next/link";
import { auth } from "@/lib/auth/auth";
import { redirect } from "next/navigation";
import { isPlatformAdminEmail } from "@/lib/platform-admin";
import { Building2, Clock, FileText, FolderKanban } from "lucide-react";
import { Button } from "@/components/ui/button";
import { QuotationForm } from "@/components/landing/quotation-form";

export default async function RootPage() {
  const session = await auth();

  if (session?.user) {
    if (session.user.userType === "STAFF" && session.user.organizationSlug) {
      redirect(`/${session.user.organizationSlug}`);
    }
    if (session.user.userType === "CUSTOMER_CONTACT" && session.user.organizationSlug) {
      redirect(`/portal/${session.user.organizationSlug}`);
    }
    if (
      session.user.userType === "STAFF" &&
      !session.user.organizationSlug &&
      isPlatformAdminEmail(session.user.email)
    ) {
      redirect("/admin");
    }
    redirect("/auth/login");
  }

  return (
    <div className="min-h-screen bg-[var(--background)] text-[var(--foreground)]">
      <header className="border-b border-[var(--border)] bg-[var(--background)]/80 backdrop-blur-sm sticky top-0 z-10">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-4 py-4">
          <Link href="/" className="flex items-center gap-2">
            <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-[var(--primary)] text-white">
              <Building2 className="h-5 w-5" />
            </span>
            <span className="font-semibold tracking-tight">Workspace</span>
          </Link>
          <Button asChild size="sm">
            <Link href="/auth/login">Sign in</Link>
          </Button>
        </div>
      </header>

      <main>
        <section className="mx-auto max-w-5xl px-4 py-16 md:py-24">
          <p className="mb-4 text-sm font-medium uppercase tracking-wide text-[var(--muted-foreground)]">
            Operations for teams
          </p>
          <h1 className="text-balance text-4xl font-bold tracking-tight md:text-5xl">
            Project work, time, and billing in one place
          </h1>
          <p className="mt-6 max-w-2xl text-lg text-[var(--muted-foreground)] leading-relaxed">
            A straightforward workspace for running client work: plan projects and tasks, capture time,
            and turn work into invoices without jumping between tools.
          </p>
          <div className="mt-10 flex flex-wrap items-center gap-3">
            <Button asChild size="lg">
              <Link href="/auth/login">Sign in</Link>
            </Button>
            <Button asChild variant="outline" size="lg">
              <a href="#quotation">Request a quotation</a>
            </Button>
          </div>
        </section>

        <section className="border-t border-[var(--border)] bg-[var(--muted)]/30 py-16 md:py-20">
          <div className="mx-auto max-w-5xl px-4">
            <h2 className="text-2xl font-semibold tracking-tight md:text-3xl">Built for how you work</h2>
            <ul className="mt-10 grid gap-8 sm:grid-cols-3">
              <li className="rounded-xl border border-[var(--border)] bg-[var(--card)] p-6 shadow-sm">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-[var(--primary)]/10 text-[var(--primary)]">
                  <FolderKanban className="h-5 w-5" />
                </div>
                <h3 className="mt-4 font-semibold">Projects &amp; tasks</h3>
                <p className="mt-2 text-sm text-[var(--muted-foreground)] leading-relaxed">
                  Organize work by client and project, keep tasks moving, and give your team a single place to
                  collaborate.
                </p>
              </li>
              <li className="rounded-xl border border-[var(--border)] bg-[var(--card)] p-6 shadow-sm">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-[var(--primary)]/10 text-[var(--primary)]">
                  <Clock className="h-5 w-5" />
                </div>
                <h3 className="mt-4 font-semibold">Time tracking</h3>
                <p className="mt-2 text-sm text-[var(--muted-foreground)] leading-relaxed">
                  Log time manually or with a timer so billable work is captured accurately and ready for review.
                </p>
              </li>
              <li className="rounded-xl border border-[var(--border)] bg-[var(--card)] p-6 shadow-sm">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-[var(--primary)]/10 text-[var(--primary)]">
                  <FileText className="h-5 w-5" />
                </div>
                <h3 className="mt-4 font-semibold">Invoicing</h3>
                <p className="mt-2 text-sm text-[var(--muted-foreground)] leading-relaxed">
                  Turn tracked time and agreed rates into professional invoices and keep payments organized.
                </p>
              </li>
            </ul>
          </div>
        </section>

        <section id="quotation" className="mx-auto max-w-2xl scroll-mt-24 px-4 py-16 md:py-24">
          <h2 className="text-2xl font-semibold tracking-tight md:text-3xl">Request a quotation</h2>
          <p className="mt-3 text-[var(--muted-foreground)]">
            Tell us about your team and what you need. We&apos;ll follow up by email.
          </p>
          <div className="mt-8 rounded-xl border border-[var(--border)] bg-[var(--card)] p-6 shadow-sm">
            <QuotationForm />
          </div>
        </section>
      </main>

      <footer className="border-t border-[var(--border)] py-8 text-center text-sm text-[var(--muted-foreground)]">
        <p>© {new Date().getFullYear()} Workspace</p>
      </footer>
    </div>
  );
}
