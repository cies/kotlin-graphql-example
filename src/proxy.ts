import { auth } from "./lib/auth/auth";
import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

const PUBLIC_PATHS = [
  "/auth/login",
  "/auth/signup",
  "/auth/error",
  "/auth/verify",
  "/sign",
  "/invoice",
  "/api/auth",
  "/api/stripe/webhook",
  "/api/stripe/platform-webhook",
  "/api/health",
  "/_next",
  "/favicon.ico",
];

function isPublicPath(pathname: string): boolean {
  if (pathname === "/") return true;
  if (/^\/portal\/[^/]+\/login$/.test(pathname)) return true;
  return PUBLIC_PATHS.some((p) => pathname.startsWith(p));
}

export default auth((req: NextRequest & { auth?: { user?: { id?: string; userType?: string; organizationSlug?: string } } | null }) => {
  const { pathname } = req.nextUrl;

  if (isPublicPath(pathname)) {
    return NextResponse.next();
  }

  const session = req.auth;

  if (!session?.user) {
    const loginUrl = new URL("/auth/login", req.url);
    loginUrl.searchParams.set("callbackUrl", pathname);
    return NextResponse.redirect(loginUrl);
  }

  const user = session.user;

  // Staff app: /{orgSlug}/...
  const staffMatch = pathname.match(/^\/([^/]+)(\/.*)?$/);
  if (staffMatch && !pathname.startsWith("/portal") && !pathname.startsWith("/api")) {
    const slug = staffMatch[1];
      if (!["auth", "sign", "invoice", "admin", "_next", "favicon.ico"].includes(slug)) {
      if (user.userType !== "STAFF") {
        return NextResponse.redirect(new URL(`/portal/${user.organizationSlug || ""}`, req.url));
      }
      if (!user.organizationSlug) {
        return NextResponse.redirect(new URL("/auth/login", req.url));
      }
      if (user.organizationSlug !== slug) {
        return NextResponse.redirect(new URL(`/${user.organizationSlug}`, req.url));
      }
    }
  }

  // Portal: /portal/{orgSlug}/...
  if (pathname.startsWith("/portal/")) {
    const portalMatch = pathname.match(/^\/portal\/([^/]+)(\/.*)?$/);
    if (portalMatch) {
      const slug = portalMatch[1];
      if (user.userType !== "CUSTOMER_CONTACT") {
        return NextResponse.redirect(new URL(`/${user.organizationSlug || ""}`, req.url));
      }
      if (!user.organizationSlug) {
        return NextResponse.redirect(new URL("/auth/login", req.url));
      }
      if (user.organizationSlug !== slug) {
        return NextResponse.redirect(new URL(`/portal/${user.organizationSlug}`, req.url));
      }
    }
  }

  return NextResponse.next();
});

export const config = {
  matcher: [
    "/((?!_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp)$).*)",
  ],
};
